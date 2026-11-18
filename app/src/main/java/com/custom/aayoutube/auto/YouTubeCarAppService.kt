package com.custom.aayoutube.auto

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

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
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
                override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
                    val surface = surfaceContainer.surface ?: return
                    val width = if (surfaceContainer.width > 0) surfaceContainer.width else 1280
                    val height = if (surfaceContainer.height > 0) surfaceContainer.height else 720
                    val dpi = if (surfaceContainer.dpi > 0) surfaceContainer.dpi else 160

                    mainHandler.post {
                        try {
                            val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                            virtualDisplay = displayManager.createVirtualDisplay(
                                "AAYouTubeDisplay",
                                width,
                                height,
                                dpi,
                                surface,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            )

                            val display = virtualDisplay?.display ?: return@post
                            presentation = YouTubePresentation(carContext, display).apply {
                                show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
                    mainHandler.post {
                        try {
                            presentation?.dismiss()
                        } catch (_: Exception) {}
                        presentation = null
                        virtualDisplay?.release()
                        virtualDisplay = null
                    }
                }

                override fun onClick(x: Float, y: Float) {
                    mainHandler.post { presentation?.dispatchTap(x, y) }
                }

                override fun onScroll(distanceX: Float, distanceY: Float) {
                    mainHandler.post { presentation?.dispatchScroll(distanceX, distanceY) }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            // Forces software compositing so video frames render inside VirtualDisplay instead of black DRM/overlay box
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)

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
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl("https://m.youtube.com")
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
        webView?.loadUrl("https://m.youtube.com")
    }

    fun reload() {
        webView?.reload()
    }
}
