package com.custom.aayoutube.auto

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator

class YouTubeCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                return YouTubeSurfaceScreen(carContext)
            }
        }
    }
}

class YouTubeSurfaceScreen(carContext: CarContext) : Screen(carContext) {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: YouTubePresentation? = null

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
                val surface = surfaceContainer.surface ?: return
                val width = surfaceContainer.width
                val height = surfaceContainer.height
                val dpi = surfaceContainer.dpi

                val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                virtualDisplay = displayManager.createVirtualDisplay(
                    "AAYouTubeDisplay",
                    width,
                    height,
                    dpi,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                )

                virtualDisplay?.display?.let { display ->
                    presentation = YouTubePresentation(carContext, display)
                    presentation?.show()
                }
            }

            override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
                presentation?.dismiss()
                presentation = null
                virtualDisplay?.release()
                virtualDisplay = null
            }

            override fun onClick(x: Float, y: Float) {
                presentation?.dispatchTap(x, y)
            }

            override fun onScroll(distanceX: Float, distanceY: Float) {
                presentation?.dispatchScroll(distanceX, distanceY)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Back")
                    .setOnClickListener {
                        if (presentation?.goBack() != true) {
                            screenManager.pop()
                        }
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Home")
                    .setOnClickListener {
                        presentation?.loadHome()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Reload")
                    .setOnClickListener {
                        presentation?.reload()
                    }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }
}

class YouTubePresentation(context: Context, display: Display) : Presentation(context, display) {
    var webView: WebView? = null
        private set

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = WebView(context).apply {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("https://www.youtube.com")
        }

        webView = view
        setContentView(view)
    }

    fun dispatchTap(x: Float, y: Float) {
        val wv = webView ?: return
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val upEvent = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
        wv.dispatchTouchEvent(downEvent)
        wv.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
    }

    fun dispatchScroll(distanceX: Float, distanceY: Float) {
        webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
    }

    fun goBack(): Boolean {
        val wv = webView ?: return false
        return if (wv.canGoBack()) {
            wv.goBack()
            true
        } else {
            false
        }
    }

    fun loadHome() {
        webView?.loadUrl("https://www.youtube.com")
    }

    fun reload() {
        webView?.reload()
    }
}
