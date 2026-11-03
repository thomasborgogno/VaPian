package com.bortho.autovelox.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bortho.autovelox.core.model.SpeedCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Seconds during which a report can still be undone; only after that is it pushed to the phone. */
internal const val UNDO_SECONDS = 5

/**
 * The "report a camera" flow, independent of how it is drawn: [report] saves the report locally and opens a
 * [UNDO_SECONDS]-second window ([pending], [secondsLeft]) during which [undo] takes it back; when the window closes the
 * report is committed. [pulses] increases with every report so the UI can play its animation. A failure is never
 * silent: [notice] says why nothing happened.
 */
@Stable
internal class ReportFlow(
    private val scope: CoroutineScope,
    private val haptic: HapticFeedback,
    private val doReport: suspend () -> SpeedCamera?,
    private val doUndo: suspend (SpeedCamera) -> Unit,
    private val doCommit: () -> Unit,
) {
    var pending by mutableStateOf<SpeedCamera?>(null)
        private set
    var secondsLeft by mutableIntStateOf(0)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var pulses by mutableIntStateOf(0)
        private set

    fun report() {
        if (pending != null) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val camera = doReport()
            if (camera == null) {
                notice = "Nessuna posizione GPS: riprova tra qualche secondo"
                delay(4_000)
                notice = null
                return@launch
            }
            pending = camera
            pulses++
            for (left in UNDO_SECONDS downTo 1) {
                secondsLeft = left
                delay(1_000)
                if (pending != camera) return@launch // undone
            }
            pending = null
            doCommit()
        }
    }

    fun undo() {
        val camera = pending ?: return
        pending = null
        scope.launch { doUndo(camera) }
    }
}

@Composable
internal fun rememberReportFlow(
    report: suspend () -> SpeedCamera?,
    undo: suspend (SpeedCamera) -> Unit,
    commit: () -> Unit,
): ReportFlow {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // bound method references are new objects on every recomposition: always call through the latest ones
    val latestReport by rememberUpdatedState(report)
    val latestUndo by rememberUpdatedState(undo)
    val latestCommit by rememberUpdatedState(commit)
    return remember(scope, haptic) { ReportFlow(scope, haptic, { latestReport() }, { latestUndo(it) }, { latestCommit() }) }
}
