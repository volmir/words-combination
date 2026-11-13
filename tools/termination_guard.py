#!/usr/bin/env python3
"""SEL-05: управляемое завершение длинных команд с дочерними процессами ИИ.

Классификаторы LEX-03/LEX-04 запускают внешние CLI (Codex/OpenCode) дочерними
процессами. Чтобы ни один дочерний процесс не пережил управляемое завершение
вызывающей команды (SIGTERM оркестратора, SIGINT пользователя), каждая из
вызывающих CLI (prepare_v2, classify_word_categories, assess_word_suitability)
устанавливает этот страж:

- команда переходит в собственную сессию/группу процессов (`setsid`), поэтому
  все её дочерние и внучатые процессы находятся в одной группе;
- обработчик SIGTERM/SIGINT сначала отправляет SIGTERM всей группе, при
  повторном сигнале — SIGKILL, затем выходит с кодом 143/130.

Это дополнение, а не замена политик таймаутов и отката публикации: частичные
батчи остаются в поштучных кэшах, пакет уничтожается только транзакцией publish.
"""
import os
import signal

TERMINATED_EXIT_CODES = {signal.SIGTERM: 143, signal.SIGINT: 130}


def become_group_leader():
    """Создать собственную сессию/группу процессов, если она ещё не создана.

    После этого `os.killpg(0, ...)` адресует саму команду и всё её дерево,
    включая внуков, порождённых CLI моделей.
    """
    if not hasattr(os, "setsid"):
        return
    if os.getpgrp() == os.getpid():
        return
    try:
        os.setsid()
    except OSError:
        # Уже лидер группы или сессии; текущая группа всё равно адресуется.
        pass


def install_termination_handler():
    """Обработчик, завершающий всю группу процессов по SIGTERM/SIGINT.

    Первый сигнал завершает группу SIGTERM; отложенный/повторный сигнал эскалирует
    до SIGKILL всей группы, поэтому потомков, игнорирующих SIGTERM, тоже не
    остаётся. Процесс покидает группу с кодом терминации (по факту — УБИВАЕТСЯ
    эскалацией, returncode<0): это осознанная цена гарантии «ни один дочерний
    процесс не переживает управляемое завершение». Журналы публикации
    восстанавливаются следующим запуском; парциальные ИИ-решения сохраняются
    поштучными кэшами заранее.
    """
    state = {"escalated": False}

    def terminate(signum, _frame):
        if state["escalated"]:
            try:
                os.killpg(0, signal.SIGKILL)
            except OSError:
                pass
            os._exit(TERMINATED_EXIT_CODES.get(signum, 137))
        state["escalated"] = True
        try:
            os.killpg(0, signum)
        except OSError:
            # Группа уже исчезла (например, дети завершились сами).
            os._exit(TERMINATED_EXIT_CODES.get(signum, 137))
        raise SystemExit(TERMINATED_EXIT_CODES.get(signum, 143)) from None

    for signum in TERMINATED_EXIT_CODES:
        try:
            signal.signal(signum, terminate)
        except (OSError, ValueError):
            # Не основной поток или среда без поддержки сигналов.
            pass
