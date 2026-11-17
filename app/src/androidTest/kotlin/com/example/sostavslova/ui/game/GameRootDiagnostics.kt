package com.example.sostavslova.ui.game

import android.app.Activity
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Reads the actual windows before Compose/ActivityScenario teardown; never selects another root. */
@OptIn(InternalComposeUiApi::class)
internal class GameRootDiagnostics : ExternalResource() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val activities = linkedSetOf<Activity>()
    private val callback = ActivityLifecycleCallback { activity, stage ->
        activities.add(activity)
        Log.i(TAG, "lifecycle ${identity(activity)} $stage changing=${activity.isChangingConfigurations} finishing=${activity.isFinishing}")
    }
    val failure = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            // Diagnostic errors must never replace the original test failure.
            runCatching { capture("failure ${description.methodName}: $e") }
                .onFailure { Log.e(TAG, "diagnostics failed", it) }
        }
    }
    override fun before() {
        instrumentation.runOnMainSync {
            activities.clear()
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
    }
    override fun after() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
            activities.clear()
        }
    }
    fun capture(phase: String) {
        Log.i(TAG, "$phase uptime=${SystemClock.uptimeMillis()} runner=${instrumentation.javaClass.name} pid=${android.os.Process.myPid()}")
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().forEach { activities.addAll(monitor.getActivitiesInStage(it)) }
            activities.forEach { activity ->
                Log.i(TAG, "activity ${identity(activity)} stage=${monitor.getLifecycleStageOf(activity)} changing=${activity.isChangingConfigurations} destroyed=${activity.isDestroyed} finishing=${activity.isFinishing} focus=${activity.hasWindowFocus()}")
            }
            val windows = if (Build.VERSION.SDK_INT >= 29) {
                WindowInspector.getGlobalWindowViews()
            } else {
                activities.filterNot { it.isDestroyed }.map { it.window.decorView }
            }
            windows.forEach { decor ->
                Log.i(TAG, "window ${identity(decor)} attached=${decor.isAttachedToWindow} visibility=${decor.windowVisibility} focus=${decor.hasWindowFocus()}")
                fun visit(view: View) {
                    if (view is ViewRootForTest) {
                        Log.i(TAG, "compose ${identity(view)} attached=${view.isAttachedToWindow} shown=${view.isShown} resumed=${view.isLifecycleInResumedState} semanticsChildren=${view.semanticsOwner.rootSemanticsNode.children.size}")
                    }
                    if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
                }
                visit(decor)
            }
        }
        val markers = listOf(
            "ActiveInstrumentation", "Active instrumentation", "mFinished=", "WordsTestRunner",
            "mInstr=", "mWatcher=", "mUiAutomationConnection=", "mWakefulness=",
            "mCurrentFocus=", "mFocusedApp=", "mObscuringWindow=", "mTopFocusedDisplayId=",
            "mShowingLockscreen=", "mDreamingLockscreen=", "mResumedActivity", "ResumedActivity:",
            "mSleeping="
        )
        listOf(
            "dumpsys power", "dumpsys window", "dumpsys activity activities", "dumpsys activity processes"
        ).forEach { command ->
            shell(command).lineSequence().filter { line -> markers.any(line::contains) }
                .forEach { Log.i(TAG, "$command: $it") }
        }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private fun identity(value: Any) = "${value.javaClass.simpleName}@${System.identityHashCode(value).toString(16)}"
    private companion object { const val TAG = "G12Roots" }
}
