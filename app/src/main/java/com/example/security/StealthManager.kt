package com.example.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object StealthManager {

    private const val TAG = "StealthManager"
    private const val MAIN_ALIAS = "com.example.MainLauncherAlias"
    private const val CALCULATOR_ALIAS = "com.example.CalculatorDisguiseAlias"

    fun applyCalculatorDisguise(context: Context, enabled: Boolean): Boolean {
        return try {
            val pm = context.applicationContext.packageManager
            val pkg = context.applicationContext.packageName
            val mainComponent = ComponentName(pkg, MAIN_ALIAS)
            val calcComponent = ComponentName(pkg, CALCULATOR_ALIAS)

            if (enabled) {
                pm.setComponentEnabledSetting(
                    calcComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    mainComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } else {
                pm.setComponentEnabledSetting(
                    mainComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    calcComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to update launcher alias disguise", t)
            false
        }
    }
}
