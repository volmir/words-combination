"""Файловый обмен и необязательные API; запросы содержат только разрешённые поля."""
import hashlib
import json
import os
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit


def json_digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, staged = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(value, output, ensure_ascii=False, sort_keys=True)
            output.write("\n")
        os.replace(staged, path)
    finally:
        if os.path.exists(staged):
            os.unlink(staged)


def prompt_hash(prompt):
    return hashlib.sha256(prompt.encode()).hexdigest()


class Provider:
    def __init__(self, cache, model, validators, config):
        self.cache, self.model = Path(cache), model
        self.validators, self.config = validators, config
        for name in ("requests", "responses", "answers"):
            (self.cache / name).mkdir(parents=True, exist_ok=True)
        self.requests = {}
        for path in sorted((self.cache / "requests").glob("*.jsonl")):
            for line in path.read_text(encoding="utf-8").splitlines():
                request = json.loads(line)
                body = {key: value for key, value in request.items() if key != "request_id"}
                if request.get("request_id") != json_digest(body) or request.get("prompt_sha256") != prompt_hash(request.get("prompt", "")):
                    raise ValueError("Хэш экспортированного запроса изменился: " + str(path))
                self.validate_items(request["task"], request["items"])
                self.requests[request["request_id"]] = request
        self.imported = set()
        self.active_requests = set()

    def validate_items(self, task, items):
        fields = ({"word", "diminutive_candidate"} if task == "definition" else
                  {"id", "category", "definition", "length", "first_letter"})
        if task not in self.validators or not isinstance(items, list) or any(set(item) != fields for item in items):
            raise ValueError("Запрос содержит поля вне разрешённой схемы")

    def answer_path(self, task, identity, digest, model=None):
        return self.cache / "answers" / (json_digest([task, identity, digest, model or self.model]) + ".json")

    def store(self, request, results):
        if not isinstance(results, list):
            raise ValueError("results должен быть списком")
        identity = "id" if request["task"] == "guess" else "word"
        expected = {item[identity] for item in request["items"]}
        seen = set()
        validated = []
        for result in results:
            self.validators[request["task"]](result)
            key = result[identity]
            if key not in expected or key in seen:
                raise ValueError("Посторонний или повторный результат: " + key)
            seen.add(key)
            path = self.answer_path(request["task"], key, request["prompt_sha256"], request["model"])
            if path.exists() and json.loads(path.read_text()) != result:
                raise ValueError("Конфликт ответов в кэше: " + key)
            validated.append((path, result))
        for path, result in validated:
            atomic_json(path, result)

    def import_responses(self, task, digest):
        if (task, digest) in self.imported:
            return
        for path in sorted((self.cache / "responses").glob("*.jsonl")):
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if not line.strip():
                    continue
                response = json.loads(line)
                request = self.requests.get(response.get("request_id"))
                if request is None or response.get("model") != request["model"]:
                    raise ValueError("Неизвестный запрос или другая модель: %s:%s" % (path, number))
                if request["model"] != self.model or request["task"] != task or request["prompt_sha256"] != digest:
                    continue
                self.store(request, response.get("results"))
        self.imported.add((task, digest))

    def exchange(self, task, prompt, items):
        self.validate_items(task, items)
        digest = prompt_hash(prompt)
        self.import_responses(task, digest)
        identity = "id" if task == "guess" else "word"
        results = {}
        # Состав пачки стабилен при частичном импорте и повторном запуске.
        for offset in range(0, len(items), self.config["batch_size"]):
            batch = items[offset:offset + self.config["batch_size"]]
            request = {"task": task, "model": self.model, "prompt": prompt,
                       "prompt_sha256": digest, "items": batch}
            request["request_id"] = json_digest(request)
            self.active_requests.add(request["request_id"])
            missing = [item for item in batch if not self.answer_path(task, item[identity], digest).exists()]
            if missing:
                path = self.cache / "requests" / (task + "-" + request["request_id"] + ".jsonl")
                if not path.exists():
                    atomic_json(path, request)
                self.requests[request["request_id"]] = request
                response = self.complete(request)
                if response is not None:
                    self.store(request, response)
            for item in batch:
                path = self.answer_path(task, item[identity], digest)
                if path.exists():
                    result = json.loads(path.read_text(encoding="utf-8"))
                    self.validators[task](result)
                    results[item[identity]] = result
        return results

    def complete(self, request):
        raise NotImplementedError

    def usage_summary(self):
        totals = {"responses": 0, "input_tokens": 0, "output_tokens": 0, "cached_input_tokens": 0}
        for path in (self.cache / "responses").glob("*.jsonl"):
            for line in path.read_text(encoding="utf-8").splitlines():
                response = json.loads(line)
                if response.get("request_id") not in self.active_requests or not response.get("usage"):
                    continue
                usage = response["usage"]
                totals["responses"] += 1
                for key in ("input_tokens", "output_tokens"):
                    totals[key] += usage.get(key, 0)
                totals["cached_input_tokens"] += usage.get("input_tokens_details", {}).get("cached_tokens", 0)
        totals["available"] = bool(totals["responses"])
        return totals


class FileProvider(Provider):
    def complete(self, request):
        return None


class ApiProvider(Provider):
    def __init__(self, cache, model, validators, config, provider, allow_network=False):
        if not allow_network:
            raise ValueError("Для API необходим явный флаг --allow-network")
        self.provider = provider
        variable = "OPENAI_API_KEY" if provider == "openai" else "ANTHROPIC_API_KEY"
        self.key = os.environ.get(variable)
        if not self.key:
            raise ValueError("Не задана переменная " + variable)
        self.last_request = None
        super().__init__(cache, model, validators, config)

    def complete(self, request):
        content = json.dumps({"items": request["items"]}, ensure_ascii=False)
        if self.provider == "openai":
            url = "https://api.openai.com/v1/responses"
            payload = {"model": self.model, "instructions": request["prompt"], "input": content,
                       "temperature": 0, "store": False, "max_output_tokens": self.config["max_output_tokens"],
                       "text": {"format": {"type": "json_object"}}}
            if self.model == "gpt-6-luna":
                payload["reasoning"] = {"effort": "none"}
            headers = {"Authorization": "Bearer " + self.key}
        else:
            url = "https://api.anthropic.com/v1/messages"
            payload = {"model": self.model, "system": request["prompt"],
                       "messages": [{"role": "user", "content": content}], "temperature": 0,
                       "max_tokens": self.config["max_output_tokens"]}
            # Эта версия поддерживает запрошенный temperature=0; новые версии меняют контракт.
            headers = {"x-api-key": self.key, "anthropic-version": "2023-06-01"}
        headers["Content-Type"] = "application/json"
        for attempt in range(self.config["api_retries"] + 1):
            if self.last_request is not None:
                time.sleep(max(0, 60 / self.config["requests_per_minute"] - (time.monotonic() - self.last_request)))
            self.last_request = time.monotonic()
            try:
                call = urllib.request.Request(url, json.dumps(payload).encode(), headers)
                with urllib.request.urlopen(call, timeout=self.config["api_timeout_seconds"]) as response:
                    data = json.load(response)
                if self.provider == "openai":
                    if data.get("status") != "completed":
                        raise ValueError("OpenAI: неполный ответ; кэш не обновлён")
                    text = "".join(part.get("text", "") for item in data.get("output", [])
                                   if item.get("type") == "message" for part in item.get("content", [])
                                   if part.get("type") == "output_text")
                else:
                    if data.get("stop_reason") != "end_turn":
                        raise ValueError("Anthropic: неполный ответ; кэш не обновлён")
                    text = "".join(part.get("text", "") for part in data.get("content", []) if part.get("type") == "text")
                results = json.loads(text)["results"]
                self.store(request, results)
                atomic_json(self.cache / "responses" / (request["request_id"] + ".jsonl"),
                            {"request_id": request["request_id"], "model": self.model, "results": results,
                             "usage": data.get("usage", {})})
                return results
            except (urllib.error.URLError, TimeoutError) as error:
                retryable = not isinstance(error, urllib.error.HTTPError) or error.code == 429 or error.code >= 500
                if not retryable or attempt == self.config["api_retries"]:
                    raise ValueError("API недоступен или отклонил запрос; завершённые ответы сохранены, ключи не записаны") from None
                time.sleep(min(60, 2 ** attempt))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise ValueError("Перенаправления локального сервера запрещены")


class OllamaProvider(Provider):
    """Только loopback, без прокси, облачных моделей и загрузки весов."""
    def __init__(self, cache, model, validators, config, allow_network=False, url="http://127.0.0.1:11434"):
        if not allow_network:
            raise ValueError("Для локального HTTP необходим явный флаг --allow-network")
        parts = urlsplit(url)
        if (parts.scheme != "http" or parts.hostname not in ("127.0.0.1", "localhost", "::1") or
                parts.username is not None or parts.password is not None or parts.path not in ("", "/") or
                parts.query or parts.fragment):
            raise ValueError("Ollama доступна только по локальному HTTP-адресу без учётных данных")
        if model.endswith("-cloud") or ":cloud" in model:
            raise ValueError("Облачные модели Ollama запрещены в локальном провайдере")
        local_config = dict(config, batch_size=config.get("ollama_batch_size", 4))
        super().__init__(cache, model, validators, local_config)
        self.url = url.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def complete(self, request):
        payload = {"model": self.model, "stream": False, "think": False, "format": "json", "keep_alive": "1m",
                   "messages": [{"role": "system", "content": request["prompt"]},
                                {"role": "user", "content": json.dumps({"items": request["items"]}, ensure_ascii=False)}],
                   "options": {"temperature": 0, "num_ctx": self.config.get("ollama_context_length", 8192),
                               "num_predict": self.config.get("ollama_max_output_tokens", 2048)}}
        for attempt in range(self.config["api_retries"] + 1):
            try:
                call = urllib.request.Request(self.url + "/api/chat", json.dumps(payload).encode(),
                                              {"Content-Type": "application/json"})
                with self.opener.open(call, timeout=self.config.get("ollama_timeout_seconds", 600)) as response:
                    data = json.load(response)
                if data.get("done") is not True or data.get("done_reason") != "stop":
                    raise ValueError("Ollama: неполный ответ; уменьшите пачку или увеличьте лимит вывода")
                results = json.loads(data["message"]["content"])["results"]
                if len(results) != len(request["items"]):
                    raise ValueError("Ollama: пропущены слова; уменьшите пачку, завершённые ответы сохранены")
                self.store(request, results)
                atomic_json(self.cache / "responses" / (request["request_id"] + ".jsonl"),
                            {"request_id": request["request_id"], "model": self.model, "results": results,
                             "usage": {"input_tokens": data.get("prompt_eval_count", 0),
                                       "output_tokens": data.get("eval_count", 0)},
                             "local": True, "total_duration_ns": data.get("total_duration")})
                return results
            except (urllib.error.URLError, TimeoutError) as error:
                retryable = not isinstance(error, urllib.error.HTTPError) or error.code == 429 or error.code >= 500
                if not retryable or attempt == self.config["api_retries"]:
                    raise ValueError("Локальный Ollama недоступен: запустите сервер и заранее загрузите выбранную модель") from None
                time.sleep(min(60, 2 ** attempt))
