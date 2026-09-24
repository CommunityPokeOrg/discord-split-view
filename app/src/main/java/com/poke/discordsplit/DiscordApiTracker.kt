package com.poke.discordsplit

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Passively observes Discord's HTTP traffic (okhttp, which React Native's
 * NetworkingModule uses on Android) to capture two things:
 *
 *  - the bearer token Discord puts on REST calls ("Authorization" header), so
 *    the split pane's WebView can reuse the session; and
 *  - the id of the channel the user is currently reading, observed from
 *    /channels/{id}/... requests, for "follow" mode.
 */
object DiscordApiTracker {

    @Volatile
    var authToken: String? = null
        private set

    @Volatile
    var lastChannelId: String? = null
        private set

    private val channelPathRegex = Regex("/api/v\\d+/channels/(\\d+)/")

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun install(classLoader: ClassLoader) {
        try {
            val builder = XposedHelpers.findClass("okhttp3.Request\$Builder", classLoader)

            val headerHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    val value = param.args.getOrNull(1) as? String ?: return
                    if (name.equals("authorization", ignoreCase = true) && value.isNotBlank()) {
                        authToken = value
                        notifyChanged()
                    }
                }
            }
            XposedBridge.hookAllMethods(builder, "header", headerHook)
            XposedBridge.hookAllMethods(builder, "addHeader", headerHook)

            XposedBridge.hookAllMethods(
                builder,
                "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val request = param.result ?: return
                        val url = runCatching {
                            XposedHelpers.callMethod(request, "url").toString()
                        }.getOrNull() ?: return
                        channelPathRegex.find(url)?.let { match ->
                            lastChannelId = match.groupValues[1]
                            notifyChanged()
                        }
                    }
                },
            )
            XposedBridge.log("[DiscordSplitView] okhttp hooks installed")
        } catch (t: Throwable) {
            XposedBridge.log("[DiscordSplitView] okhttp hooks unavailable: $t")
        }
    }

    private fun notifyChanged() {
        for (l in listeners) runCatching { l() }
    }
}
