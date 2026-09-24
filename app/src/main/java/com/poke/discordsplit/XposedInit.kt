package com.poke.discordsplit

import android.app.Activity
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

private const val DISCORD_PACKAGE = "com.discord"

// Packages that host their own in-process Activities inside Discord; injecting
// the split-view UI there would be wrong.
private val FOREIGN_ACTIVITY_PREFIXES = listOf(
    "android.",
    "androidx.",
    "com.google.",
    "com.facebook.",
    "com.android.",
)

class XposedInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != DISCORD_PACKAGE) return
        if (lpparam.processName != lpparam.packageName) return

        XposedBridge.log("[DiscordSplitView] loaded in ${lpparam.processName}")

        DiscordApiTracker.install(lpparam.classLoader)

        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (isForeignActivity(activity)) return
                    SplitViewController.attach(activity)
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onDestroy",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    SplitViewController.detach(activity)
                }
            },
        )
    }

    private fun isForeignActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return FOREIGN_ACTIVITY_PREFIXES.any { name.startsWith(it) }
    }
}
