# Две демонстрационные задачи (не входят в рабочую очередь)

## DEMO-1: Создать заметку
tier: light
gate: python3 -c "from pathlib import Path; assert Path('ORCHESTRATOR_DEMO.md').read_text(encoding='utf-8').startswith('# Пример')"
retries: 1
```prompt
Создай в корне ORCHESTRATOR_DEMO.md с заголовком «# Пример» и одним предложением:
«Этот файл создан последовательным оркестратором задач.»
Не меняй другие файлы.
```

## DEMO-2: Дополнить заметку
tier: light
gate: python3 -c "from pathlib import Path; text = Path('ORCHESTRATOR_DEMO.md').read_text(encoding='utf-8'); assert text.startswith('# Пример') and 'Вторая задача завершена.' in text"
retries: 1
```prompt
В существующий ORCHESTRATOR_DEMO.md добавь отдельным абзацем:
«Вторая задача завершена.» Сохрани ранее записанный текст.
Не меняй другие файлы.
```
