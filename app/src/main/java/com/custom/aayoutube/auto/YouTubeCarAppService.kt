package com.custom.aayoutube.auto

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.ViewGroup
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
import androidx.core.app.NotificationCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class YouTubeCarAppService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        startMediaForegroundService()
    }

    private fun startMediaForegroundService() {
        val channelId = "aa_youtube_playback"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "AA YouTube Background Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AA YouTube Player")
            .setContentText("Background audio & video session active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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
    private var persistentWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        try {
            val pm = carContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AAYouTube::MediaWakeLock").apply {
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val audioManager = carContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
                override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
                    val surface = surfaceContainer.surface ?: return
                    val width = if (surfaceContainer.width > 0) surfaceContainer.width else 1280
                    val height = if (surfaceContainer.height > 0) surfaceContainer.height else 720
                    val dpi = if (surfaceContainer.dpi > 0) surfaceContainer.dpi else 160

                    mainHandler.post {
                        try {
                            if (persistentWebView == null) {
                                persistentWebView = createPersistentWebView(carContext)
                            }

                            // Reuse existing VirtualDisplay if available, otherwise create once
                            if (virtualDisplay == null) {
                                val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                                virtualDisplay = displayManager.createVirtualDisplay(
                                    "AAYouTubeDisplay",
                                    width,
                                    height,
                                    dpi,
                                    surface,
                                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                                )
                            } else {
                                virtualDisplay?.resize(width, height, dpi)
                                virtualDisplay?.surface = surface
                            }

                            val display = virtualDisplay?.display ?: return@post
                            if (presentation == null) {
                                presentation = YouTubePresentation(carContext, display, persistentWebView!!).apply {
                                    show()
                                }
                            }
                            persistentWebView?.onResume()
                            persistentWebView?.resumeTimers()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
                    mainHandler.post {
                        // Detach surface ONLY — do NOT destroy Presentation or WebView so playback continues
                        try {
                            virtualDisplay?.surface = null
                            persistentWebView?.onResume()
                            persistentWebView?.resumeTimers()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun onClick(x: Float, y: Float) {
                    mainHandler.post { dispatchTap(x, y) }
                }

                override fun onScroll(distanceX: Float, distanceY: Float) {
                    mainHandler.post { persistentWebView?.scrollBy(distanceX.toInt(), distanceY.toInt()) }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPersistentWebView(context: Context): WebView {
        return WebView(context.applicationContext).apply {
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
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectBackgroundAndKeyboardScripts(view)
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl("https://www.youtube.com")
        }
    }

    private fun injectBackgroundAndKeyboardScripts(wv: WebView?) {
        val js = """
            (function() {
                // 1. Prevent YouTube from pausing video when surface is hidden by Maps or Reverse Camera
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);

                // 2. Car Touch Keyboard Overlay
                if (document.getElementById('aa-car-kb')) return;
                const kb = document.createElement('div');
                kb.id = 'aa-car-kb';
                kb.style.cssText = 'display:none;position:fixed;bottom:0;left:0;width:100%;background:#181818;z-index:999999;padding:8px;box-sizing:border-box;border-top:2px solid #444;font-family:sans-serif;';
                
                const bar = document.createElement('div');
                bar.style.cssText = 'display:flex;gap:6px;margin-bottom:6px;';
                const inp = document.createElement('input');
                inp.id = 'aa-kb-input';
                inp.placeholder = 'Type video to search...';
                inp.style.cssText = 'flex:1;font-size:18px;padding:8px 12px;border-radius:6px;border:1px solid #555;background:#000;color:#fff;';
                
                const goBtn = document.createElement('button');
                goBtn.innerText = 'GO';
                goBtn.style.cssText = 'background:#cc0000;color:#fff;font-weight:bold;font-size:16px;padding:0 20px;border:none;border-radius:6px;';
                goBtn.onclick = function() {
                    const q = encodeURIComponent(inp.value.trim());
                    kb.style.display = 'none';
                    if (q) window.location.href = 'https://www.youtube.com/results?search_query=' + q;
                };

                const closeBtn = document.createElement('button');
                closeBtn.innerText = 'X';
                closeBtn.style.cssText = 'background:#333;color:#fff;font-size:16px;padding:0 14px;border:none;border-radius:6px;';
                closeBtn.onclick = function() { kb.style.display = 'none'; };

                bar.appendChild(inp);
                bar.appendChild(goBtn);
                bar.appendChild(closeBtn);
                kb.appendChild(bar);

                const rows = [
                    ['1','2','3','4','5','6','7','8','9','0'],
                    ['Q','W','E','R','T','Y','U','I','O','P'],
                    ['A','S','D','F','G','H','J','K','L'],
                    ['Z','X','C','V','B','N','M','DEL'],
                    ['SPACE']
                ];

                rows.forEach(row => {
                    const rDiv = document.createElement('div');
                    rDiv.style.cssText = 'display:flex;justify-content:center;gap:5px;margin-bottom:5px;';
                    row.forEach(key => {
                        const btn = document.createElement('button');
                        btn.innerText = key === 'SPACE' ? 'SPACE' : key;
                        btn.style.cssText = 'flex:' + (key === 'SPACE' ? '4' : '1') + ';height:38px;font-size:16px;font-weight:bold;background:#2b2b2b;color:#fff;border:1px solid #444;border-radius:5px;';
                        btn.onclick = function(e) {
                            e.stopPropagation();
                            if (key === 'DEL') inp.value = inp.value.slice(0, -1);
                            else if (key === 'SPACE') inp.value += ' ';
                            else inp.value += key.toLowerCase();
                        };
                        rDiv.appendChild(btn);
                    });
                    kb.appendChild(rDiv);
                });

                document.body.appendChild(kb);
            })();
        """.trimIndent()
        wv?.evaluateJavascript(js, null)
    }

    private fun dispatchTap(x: Float, y: Float) {
        val wv = persistentWebView ?: return
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val upEvent = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
        wv.dispatchTouchEvent(downEvent)
        wv.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
    }

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Back")
                    .setOnClickListener {
                        if (persistentWebView?.canGoBack() == true) {
                            persistentWebView?.goBack()
                        } else {
                            screenManager.pop()
                        }
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Home")
                    .setOnClickListener {
                        persistentWebView?.loadUrl("https://www.youtube.com")
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Search")
                    .setOnClickListener {
                        persistentWebView?.evaluateJavascript(
                            "var kb = document.getElementById('aa-car-kb'); if(kb) kb.style.display = (kb.style.display === 'none' ? 'block' : 'none');",
                            null
                        )
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Reload")
                    .setOnClickListener {
                        persistentWebView?.reload()
                    }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }
}

class YouTubePresentation(
    context: Context,
    display: Display,
    private val webView: WebView
) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (webView.parent as? ViewGroup)?.removeView(webView)
        setContentView(webView)
    }
}
