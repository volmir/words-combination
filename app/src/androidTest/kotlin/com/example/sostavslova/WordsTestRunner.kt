package com.example.sostavslova

import android.app.Activity
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/** MIUI can silently reject ActivityScenario launches after installing the test target. */
class WordsTestRunner : AndroidJUnitRunner() {
    private var originalMode: String? = null
    private var targetPackage: String? = null

    override fun onStart() {
        try {
            prepareMiuiActivityLaunch()
        } catch (failure: Exception) {
            Log.e("WordsTestRunner", "Cannot prepare MIUI test Activity launches", failure)
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("shortMsg", "Cannot prepare MIUI test Activity launches")
            })
            return
        }
        super.onStart()
    }

    private fun prepareMiuiActivityLaunch() {
        if (shell("getprop ro.miui.ui.version.name").isNotBlank()) {
            val pkg = targetContext.packageName
            val state = shell("cmd appops get $pkg 10021")
            val explicitMode = Regex("MIUIOP\\(10021\\): ([a-z]+)").find(state)?.groupValues?.get(1)
            val effectiveMode = explicitMode
                ?: Regex("Default mode: ([a-z]+)").find(state)?.groupValues?.get(1)
                ?: error("Cannot determine MIUI activity launch policy for $pkg: $state")
            if (effectiveMode != "allow") {
                check(effectiveMode in setOf("ignore", "deny", "default", "foreground", "ask"))
                targetPackage = pkg
                originalMode = explicitMode ?: "default"
                shell("cmd appops set $pkg 10021 allow")
                check(shell("cmd appops get $pkg 10021").contains("MIUIOP(10021): allow")) {
                    "MIUI blocked test Activity launches: cannot temporarily allow app-op 10021 for $pkg"
                }
                Log.i("WordsTestRunner", "MIUI app-op 10021: $effectiveMode -> allow for $pkg during instrumentation")
            }
        }
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        val mode = originalMode
        val pkg = targetPackage
        originalMode = null
        if (mode != null && pkg != null) {
            try {
                shell("cmd appops set $pkg 10021 $mode")
                val restored = shell("cmd appops get $pkg 10021")
                check(if (mode == "default") !restored.contains("MIUIOP(10021): allow")
                    else restored.contains("MIUIOP(10021): $mode"))
                Log.i("WordsTestRunner", "MIUI app-op 10021 restored to $mode for $pkg")
            } catch (failure: Exception) {
                Log.e("WordsTestRunner", "Cannot restore MIUI test launch policy", failure)
                super.finish(Activity.RESULT_CANCELED, Bundle(results ?: Bundle()).apply {
                    putString("shortMsg", "Cannot restore MIUI app-op 10021 after instrumentation")
                })
                return
            }
        }
        super.finish(resultCode, results)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
}
