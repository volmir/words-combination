package com.example.sostavslova.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.sostavslova.core.InstallEvidence
import com.example.sostavslova.core.WelcomeRules

/**
 * Равенство firstInstallTime и lastUpdateTime — единственный доступный офлайн
 * признак, что пакет устанавливался, а не обновлялся. Любое отклонение или
 * ошибка пакетного менеджера трактуется как неизвестность (fail-closed).
 *
 * Ограничение: после обновления пакета lastUpdateTime уже не равен firstInstallTime,
 * поэтому очистка данных обновлённой установки не возвращает приветствие.
 */
internal fun Context.readInstallEvidence(): InstallEvidence = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    WelcomeRules.installEvidence(info.firstInstallTime, info.lastUpdateTime)
} catch (_: Exception) {
    InstallEvidence.UNKNOWN
}
