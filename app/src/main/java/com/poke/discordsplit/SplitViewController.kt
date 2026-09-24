package com.poke.discordsplit

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap

/**
 * Injected into every Discord Activity. Adds:
 *  - a floating "⇆" button that toggles the split pane, and
 *  - a resizable side pane hosting a WebView pointed at discord.com, signed in
 *    with the app's own token, so a second channel or DM can be viewed
 *    alongside the one open in the main view.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class SplitViewController private constructor(private val activity: Activity) {

    private val decor: ViewGroup get() = activity.window.decorView as ViewGroup
    private val dm = activity.resources.displayMetrics
    private val mainHandler = Handler(Looper.getMainLooper())

    private var fab: View? = null
    private var pane: View? = null
    private var webView: WebView? = null
    private var paneFraction = DEFAULT_FRACTION
    private var bootstrapped = false

    fun attach() {
        if (fab != null) return
        runCatching { addFab() }.onFailure {
            XposedBridge.log("[DiscordSplitView] attach failed: $it")
        }
    }

    fun detach() {
        mainHandler.post {
            fab?.let { decor.removeView(it) }
            pane?.let { decor.removeView(it) }
            fab = null
            pane = null
            webView = null
            bootstrapped = false
        }
    }

    // ---------- floating toggle button ----------

    private fun addFab() {
        val size = 44.dp()
        val button = TextView(activity).apply {
            text = "⇆"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE65865F2.toInt())
            }
            elevation = 20f
        }
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
        button.layoutParams = lp
        button.post {
            lp.leftMargin = dm.widthPixels - size - 16.dp()
            lp.topMargin = (dm.heightPixels * 0.4f).toInt()
            button.layoutParams = lp
        }
        button.setOnTouchListener(FabTouchListener(lp, button))
        decor.addView(button)
        fab = button
    }

    private inner class FabTouchListener(
        private val lp: FrameLayout.LayoutParams,
        private val view: View,
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startLeft = 0
        private var startTop = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) {
                        lp.leftMargin = (startLeft + dx).toInt()
                            .coerceIn(0, decor.width - view.width)
                        lp.topMargin = (startTop + dy).toInt()
                            .coerceIn(0, decor.height - view.height)
                        view.layoutParams = lp
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) togglePane()
                }
            }
            return true
        }
    }

    // ---------- split pane ----------

    private fun togglePane() {
        if (pane != null) {
            closePane()
        } else {
            openPane()
        }
    }

    private fun openPane() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundColor(0xFF1E1F22.toInt())
            addView(buildHeader())
            addView(buildWebView())
        }

        val strip = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(DRAG_STRIP_DP.dp(), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0x995865F2.toInt())
            setOnTouchListener(DividerTouchListener())
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                paneWidth(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END,
            )
            elevation = 30f
            addView(strip)
            addView(content)
        }

        decor.addView(container)
        pane = container
        loadDiscordWeb()
    }

    private fun closePane() {
        pane?.let { decor.removeView(it) }
        pane = null
        webView = null
        bootstrapped = false
    }

    private fun buildHeader(): View {
        fun headerButton(label: String, onClick: () -> Unit): TextView =
            TextView(activity).apply {
                text = label
                textSize = 12f
                setTextColor(0xFFB5BAC1.toInt())
                gravity = Gravity.CENTER
                setPadding(10.dp(), 0, 10.dp(), 0)
                setOnClickListener { onClick() }
            }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, HEADER_DP.dp(),
            )
            setBackgroundColor(0xFF111214.toInt())
            addView(
                TextView(activity).apply {
                    text = "Split"
                    textSize = 12f
                    setTextColor(0xFFDBDEE1.toInt())
                    setPadding(12.dp(), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(headerButton("Follow") { followCurrentChannel() })
            addView(headerButton("DMs") { webView?.loadUrl("https://discord.com/channels/@me") })
            addView(headerButton("◀") { webView?.let { if (it.canGoBack()) it.goBack() } })
            addView(headerButton("✕") { closePane() })
        }
    }

    private fun buildWebView(): View {
        val web = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DESKTOP_UA
            webViewClient = SplitWebViewClient()
            webChromeClient = WebChromeClient()
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView = web
        return web
    }

    private inner class DividerTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_MOVE && event.action != MotionEvent.ACTION_DOWN) {
                return true
            }
            val newWidth = (decor.width - event.rawX).toInt()
            val min = (decor.width * MIN_FRACTION).toInt()
            val max = (decor.width * MAX_FRACTION).toInt()
            pane?.layoutParams?.width = newWidth.coerceIn(min, max)
            pane?.requestLayout()
            return true
        }
    }

    // ---------- discord.com bootstrap ----------

    private fun loadDiscordWeb() {
        val web = webView ?: return
        val token = TokenStore.findToken(activity)
        if (token == null) {
            toast("No Discord token yet — open a channel first, then reopen split view")
            web.loadUrl("https://discord.com/app")
            return
        }
        bootstrapped = false
        pendingToken = token
        web.loadUrl("https://discord.com/app")
    }

    /**
     * The first navigation to discord.com is answered with a tiny local page
     * that writes the token into localStorage (must happen on the discord.com
     * origin, hence the interception) and then reloads the real app.
     */
    private inner class SplitWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (!bootstrapped && request.isForMainFrame && pendingToken != null) {
                val url = request.url.toString()
                if (url.startsWith("https://discord.com/")) {
                    bootstrapped = true
                    return htmlResponse(bootstrapHtml(pendingToken!!))
                }
            }
            return null
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val host = request.url.host ?: return false
            // Keep Discord navigation in the pane; let everything else fall
            // back to the system browser via the default handler.
            return !(host == "discord.com" || host.endsWith(".discord.com") || host.endsWith(".discordapp.com"))
        }
    }

    @Volatile
    private var pendingToken: String? = null

    private fun htmlResponse(html: String): WebResourceResponse =
        WebResourceResponse(
            "text/html", "utf-8",
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )

    private fun bootstrapHtml(token: String): String = """
        <!doctype html><html><head><meta charset="utf-8"></head><body>
        <script>
          try {
            localStorage.setItem('token', JSON.stringify(${JSONObject.quote(token)}));
            localStorage.setItem('user_id_cache', '0');
          } catch (e) {}
          location.replace('https://discord.com/app');
        </script>
        </body></html>
    """.trimIndent()

    // ---------- follow current channel ----------

    private fun followCurrentChannel() {
        val channelId = DiscordApiTracker.lastChannelId
        val token = TokenStore.findToken(activity)
        when {
            channelId == null -> toast("No channel visited yet in the main view")
            token == null -> toast("No Discord token yet — open a channel first")
            else -> {
                toast("Opening channel…")
                Thread {
                    val target = resolveChannelUrl(channelId, token)
                    mainHandler.post {
                        if (target != null) {
                            webView?.loadUrl(target)
                        } else {
                            toast("Couldn't resolve channel $channelId")
                        }
                    }
                }.start()
            }
        }
    }

    /**
     * Channel ids alone don't tell us whether they are a DM or a guild channel,
     * so resolve guild_id through the REST API before building the web URL.
     */
    private fun resolveChannelUrl(channelId: String, token: String): String? {
        return runCatching {
            val conn = URL("https://discord.com/api/v9/channels/$channelId")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", token)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val guild = json.optString("guild_id").ifEmpty { "@me" }
            "https://discord.com/channels/$guild/$channelId"
        }.onFailure {
            XposedBridge.log("[DiscordSplitView] channel resolve failed: $it")
        }.getOrNull()
    }

    // ---------- helpers ----------

    private fun paneWidth(): Int = (dm.widthPixels * paneFraction).toInt()

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun Int.dp(): Int = (this * dm.density).toInt()

    companion object {
        private const val DEFAULT_FRACTION = 0.45f
        private const val MIN_FRACTION = 0.25f
        private const val MAX_FRACTION = 0.75f
        private const val HEADER_DP = 40
        private const val DRAG_STRIP_DP = 6
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val controllers = WeakHashMap<Activity, SplitViewController>()

        fun attach(activity: Activity) {
            synchronized(controllers) {
                val existing = controllers[activity]
                if (existing != null) {
                    existing.attach()
                } else {
                    controllers[activity] = SplitViewController(activity).also { it.attach() }
                }
            }
        }

        fun detach(activity: Activity) {
            synchronized(controllers) {
                controllers.remove(activity)?.detach()
            }
        }
    }
}
