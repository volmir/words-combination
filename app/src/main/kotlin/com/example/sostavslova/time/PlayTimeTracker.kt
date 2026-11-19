package com.example.sostavslova.time

import com.example.sostavslova.core.ActiveTimeCounter
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignIdentity
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.data.PlayTimeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Загруженная игра; stars используются только как baseline при первом подключении. */
data class PlayTimeTarget(val campaign: Campaign, val levelNumber: Int, val stars: Int) {
    private val level = campaign.levels.single { it.number == levelNumber }
    /** Идентичность набора слов и уровень; SHA файла и порядок не участвуют. */
    val identity: String = "${CampaignIdentity.of(campaign)}:$levelNumber:${level.word}"

    init {
        require(stars in 0..level.maxStars)
    }
}

/** Диагностика без текстов ответов, путей и персональных данных. Не является квитанцией записи. */
data class PlayTimeWriteFailure(val levelNumber: Int, val operation: String, val pendingActiveMs: Long, val errorType: String)

/**
 * Один экземпляр в AppContainer. UI-события синхронно отрезают длительность под lock;
 * единственный consumer сохраняет неизменяемые сегменты в applicationScope.
 * Сбой оставляет сегмент в очереди с тем же sequence, включая неоднозначный commit.
 * Никаких coroutine scope из Composable/ViewModel и секундных обновлений UI.
 */
class PlayTimeTracker(
    private val store: PlayTimeStore,
    private val scope: CoroutineScope,
    private val monotonicMs: () -> Long,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val reportFailure: (PlayTimeWriteFailure) -> Unit = {},
) {
    private val lock = Any()
    private val counter = ActiveTimeCounter(monotonicMs)
    private var routeId: String? = null
    private var routeNumber: Int? = null
    private val loaded = mutableMapOf<String, PlayTimeTarget>()
    private val lastTargets = mutableMapOf<String, PlayTimeTarget>()
    private val finishedLevels = mutableSetOf<String>()
    private val resumedLevels = mutableSetOf<String>()
    private val focusedWindows = mutableSetOf<Any>()
    private var activityResumed = false
    private var drawerOpen = false
    private var exiting = false
    private var idlePaused = false
    private var lastActivityMs: Long? = null
    private var runningTarget: PlayTimeTarget? = null
    private var checkpoint: Job? = null
    private var idleJob: Job? = null

    private sealed interface Command {
        data class Initialize(val target: PlayTimeTarget) : Command
        data class Segment(val target: PlayTimeTarget, val durationMs: Long, var sequence: Long? = null) : Command
        data class Barrier(val result: CompletableDeferred<Unit>) : Command
        data object Reset : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableFailure = MutableStateFlow<PlayTimeWriteFailure?>(null)
    val writeFailure = mutableFailure.asStateFlow()

    init {
        require(idleTimeoutMs > 0L) { "idleTimeoutMs must be positive" }
        scope.launch {
            val pending = ArrayDeque<Command>()
            val sequences = mutableMapOf<String, Long>()
            for (command in commands) {
                if (command is Command.Reset) {
                    pending.clear()
                    sequences.clear()
                    mutableFailure.value = null
                    continue
                }
                if (command !is Command.Barrier) pending.addLast(command)
                try {
                    while (pending.isNotEmpty()) {
                        when (val item = pending.first()) {
                            is Command.Initialize -> {
                                val target = item.target
                                if (target.identity !in sequences) {
                                    sequences[target.identity] = store.initialize(target.campaign, target.levelNumber, target.stars).lastSequence
                                }
                            }
                            is Command.Segment -> {
                                val target = item.target
                                val sequence = item.sequence ?: Math.addExact(requireNotNull(sequences[target.identity]), 1L)
                                    .also { item.sequence = it }
                                val saved = store.appendSegment(target.campaign, target.levelNumber, sequence, item.durationMs, target.stars)
                                sequences[target.identity] = saved.lastSequence
                            }
                            is Command.Barrier, is Command.Reset -> error("Control command cannot be pending")
                        }
                        pending.removeFirst()
                    }
                    mutableFailure.value = null
                    if (command is Command.Barrier) command.result.complete(Unit)
                } catch (cancelled: CancellationException) {
                    if (command is Command.Barrier) command.result.completeExceptionally(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    val first = pending.first()
                    val target = when (first) {
                        is Command.Initialize -> first.target
                        is Command.Segment -> first.target
                        is Command.Barrier, is Command.Reset -> error("Control command cannot be pending")
                    }
                    val failure = PlayTimeWriteFailure(target.levelNumber,
                        if (first is Command.Initialize) "initialize" else "append",
                        pending.filterIsInstance<Command.Segment>().sumOf { it.durationMs }, error.javaClass.simpleName)
                    mutableFailure.value = failure
                    reportFailure(failure)
                    if (command is Command.Barrier) command.result.completeExceptionally(error)
                }
            }
        }
    }

    fun setRoute(id: String?, levelNumber: Int?) = synchronized(lock) {
        if (routeId != id || routeNumber != levelNumber) {
            stopSegment()
            idlePaused = false
        }
        routeId = id
        routeNumber = levelNumber
        reconcile()
    }

    /** Идемпотентная и дешёвая отметка пользовательской активности (касание, ввод текста). */
    fun onUserActivity() = synchronized(lock) {
        if (idlePaused) {
            idlePaused = false
            lastActivityMs = null
            reconcile()
        } else if (counter.running) {
            lastActivityMs = monotonicMs()
        }
    }

    /** null — загрузка, ошибка, закрытый/несуществующий уровень; finished — финальный экран. */
    fun updateLevel(owner: String, snapshot: PlayTimeTarget?, finished: Boolean = false) = synchronized(lock) {
        val target = snapshot?.let { initial ->
            // Повторный рендер/восстановление UI не является событием получения звезды.
            lastTargets[initial.identity] ?: initial.also {
                lastTargets[it.identity] = it
                enqueue(Command.Initialize(it))
            }
        }
        if (target == null) loaded.remove(owner) else loaded[owner] = target
        if (finished) finishedLevels.add(owner) else finishedLevels.remove(owner)
        reconcile()
    }

    /** Вызывается внутри принятой записи прогресса, переживающей отмену ViewModel. */
    fun checkpointProgress(progress: CampaignProgress) = synchronized(lock) {
        lastTargets.values.toList().forEach { previous ->
            val sameCampaign = CampaignIdentity.of(previous.campaign) == progress.campaignId ||
                previous.campaign.contentSha256 == progress.campaignId
            if (sameCampaign) {
                val level = previous.campaign.levels.single { it.number == previous.levelNumber }
                val updated = recordStars(previous.copy(stars = CampaignRules.stars(level, progress)))
                loaded.entries.forEach { entry -> if (entry.value.identity == updated.identity) entry.setValue(updated) }
            }
        }
    }

    private fun recordStars(snapshot: PlayTimeTarget): PlayTimeTarget {
        val previous = checkNotNull(lastTargets[snapshot.identity]) { "Timing baseline required before committed progress" }
        // Повторный/старый подтверждённый снимок не отменяет уже принятые звёзды.
        val target = if (snapshot.stars < previous.stars) snapshot.copy(stars = previous.stars) else snapshot
        if (target.stars > previous.stars) {
            // Сначала подтверждён прогресс, затем фиксируется время достижения звёзд.
            val duration = if (runningTarget?.identity == target.identity) counter.flush() ?: 0L else 0L
            enqueue(Command.Segment(target, duration))
            if (counter.running && runningTarget?.identity == target.identity) runningTarget = target
        }
        lastTargets[target.identity] = target
        return target
    }

    fun detachLevel(owner: String) = synchronized(lock) {
        loaded.remove(owner)
        finishedLevels.remove(owner)
        resumedLevels.remove(owner)
        reconcile()
    }

    fun setLevelResumed(owner: String, resumed: Boolean) = synchronized(lock) {
        if (resumed) resumedLevels.add(owner) else resumedLevels.remove(owner)
        reconcile()
    }

    fun setActivityResumed(resumed: Boolean) = synchronized(lock) {
        activityResumed = resumed
        reconcile()
    }

    /** Фокус Activity ИЛИ игрового Dialog; потеря фокуса обоих останавливает счёт. */
    fun setWindowFocused(window: Any, focused: Boolean) = synchronized(lock) {
        if (focused) focusedWindows.add(window) else focusedWindows.remove(window)
        reconcile()
    }

    fun setDrawerOpen(open: Boolean) = synchronized(lock) { drawerOpen = open; reconcile() }
    fun setExiting(value: Boolean) = synchronized(lock) { exiting = value; reconcile() }

    /** stop идемпотентен; дальнейший start требует нового события пригодности. */
    fun stop() = synchronized(lock) { stopSegment() }

    /**
     * Полный сброс кэшей трекера в памяти после очистки хранилища.
     * Сбрасывает lastTargets, loaded, finishedLevels, resumedLevels, маршрут и кэш sequences,
     * сохраняя активность окна/Activity, чтобы продолжение игры на уровне 1 сразу считало время.
     */
    fun resetAll() = synchronized(lock) {
        counter.stop()
        runningTarget = null
        lastActivityMs = null
        checkpoint?.cancel()
        checkpoint = null
        idleJob?.cancel()
        idleJob = null
        routeId = null
        routeNumber = null
        loaded.clear()
        lastTargets.clear()
        finishedLevels.clear()
        resumedLevels.clear()
        idlePaused = false
        exiting = false
        enqueue(Command.Reset)
    }

    /** Вызывается после ProgressRepository.awaitSaved; ошибки не превращаются в успех. */
    suspend fun awaitSaved() {
        val result = CompletableDeferred<Unit>()
        synchronized(lock) { enqueue(Command.Barrier(result)) }
        result.await()
    }

    private fun reconcile() {
        val target = loaded[routeId]?.takeIf { it.levelNumber == routeNumber }
        val eligible = target != null && routeId in resumedLevels && routeId !in finishedLevels && activityResumed &&
            focusedWindows.isNotEmpty() && !drawerOpen && !exiting && !idlePaused
        if (!eligible || runningTarget?.identity != target?.identity) stopSegment()
        if (eligible && !counter.running) {
            runningTarget = target
            lastActivityMs = null
            counter.start()
            checkpoint = scope.launch {
                while (isActive) {
                    delay(CHECKPOINT_MS)
                    synchronized(lock) {
                        if (isActive) runningTarget?.let { active -> counter.flush()?.let { enqueue(Command.Segment(active, it)) } }
                    }
                }
            }
            idleJob = scope.launch {
                var nextWaitMs = idleTimeoutMs
                while (isActive) {
                    delay(nextWaitMs)
                    nextWaitMs = synchronized(lock) {
                        if (!isActive || !counter.running) return@launch
                        val last = lastActivityMs
                        if (last == null) {
                            idlePaused = true
                            stopSegment()
                            return@launch
                        }
                        val elapsed = (monotonicMs() - last).coerceAtLeast(0L)
                        if (elapsed >= idleTimeoutMs) {
                            idlePaused = true
                            stopSegment()
                            return@launch
                        }
                        idleTimeoutMs - elapsed
                    }
                }
            }
        }
    }

    private fun stopSegment() {
        val duration = counter.stop()
        val target = runningTarget
        runningTarget = null
        lastActivityMs = null
        checkpoint?.cancel()
        checkpoint = null
        idleJob?.cancel()
        idleJob = null
        if (duration != null && target != null) enqueue(Command.Segment(target, duration))
    }

    private fun enqueue(command: Command) { check(commands.trySend(command).isSuccess) }

    companion object {
        const val CHECKPOINT_MS = 60_000L
        const val DEFAULT_IDLE_TIMEOUT_MS = 90_000L
    }
}
