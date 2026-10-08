package com.example.cardengine

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.UUID
import com.example.cardengine.util.SoundManager
import com.example.cardengine.experience.ExperienceResolver
import com.example.cardengine.experience.FloatingWebExperienceView

class MainActivity : AppCompatActivity(), WebAppBridge.Host {

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var renderView: RenderOverlayView
    private lateinit var floatingExperience: FloatingWebExperienceView
    private val experienceResolver by lazy { ExperienceResolver { serverUrl } }
    private lateinit var webView: WebView

    private lateinit var vault: VaultHelper
    private lateinit var vaultStore: VaultStore

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private var serverUrl: String = ""
    private var serverStatus: String = "unknown"

    private var catalog: CardCatalog? = null
    private var engine: CardEngine? = null
    private val metaById = HashMap<String, CardCatalog.LoadedCard>()

    private val animCacheDir by lazy { File(cacheDir, "anims").apply { mkdirs() } }
    private val renderCacheDir by lazy { File(cacheDir, "renders").apply { mkdirs() } }
    private val seenDir by lazy { File(filesDir, "seen").apply { mkdirs() } }

    private val processingQrs = mutableSetOf<String>()

    private var activeTradeCardId: String? = null
    private var activeTradeToken: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val bgExecutor = Executors.newCachedThreadPool()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var isScanning = false
    private var torchOn = false

    @Volatile private var scanRequested = false
    @Volatile private var shuttingDown = false
    private var scanGeneration = 0L

    private var bytes = ByteArray(0)
    private val raw by lazy { Mat() }
    private val upright by lazy { Mat() }

    private var lastSentCornerKey: String = ""
    private var lastShownCardId: String? = null
    private var lastRequestedRenderId: String? = null
    private var lastPreloadedAnimsForCard: String? = null

    private var hologramActive = false

    private val scanner by lazy { BarcodeScanning.getClient() }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        if (scanRequested && !shuttingDown && !isFinishing && !isDestroyed) {
            beginScan()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoundManager.init(this)

        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "OpenCV failed to load", Toast.LENGTH_LONG).show()
            return
        }

        vault = VaultHelper(this)
        vaultStore = VaultStore(this)

        serverUrl = prefs.getString("serverUrl", "https://serverstat-cpsy.onrender.com") ?: ""

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            visibility = View.GONE
        }
        renderView = RenderOverlayView(this).apply { visibility = View.GONE }
        floatingExperience = FloatingWebExperienceView(this)

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true

            addJavascriptInterface(WebAppBridge(this@MainActivity), "CardVision")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()

                    val corsHeaders = mutableMapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, OPTIONS",
                        "Access-Control-Allow-Headers" to "*"
                    )

                    if (request.method.equals("OPTIONS", ignoreCase = true)) {
                        return WebResourceResponse("text/plain", "UTF-8", 200, "OK", corsHeaders, ByteArrayInputStream(ByteArray(0)))
                    }

                    if (url == "https://cardvision.local/index.html") {
                        try {
                            return WebResourceResponse("text/html", "UTF-8", 200, "OK", corsHeaders, assets.open("ui/index.html"))
                        } catch (e: Exception) { e.printStackTrace() }
                    } else if (url.startsWith("https://cardvision.local/render/")) {
                        val id = url.substringAfterLast("/")
                        val imageBytes = getOfflineRender(id)
                        if (imageBytes != null) {
                            return WebResourceResponse("image/png", "UTF-8", 200, "OK", corsHeaders, ByteArrayInputStream(imageBytes))
                        }
                    } else if (url.startsWith("https://cardvision.local/anim/")) {
                        val uri = Uri.parse(url)
                        val segments = uri.pathSegments
                        if (segments.size >= 3) {
                            val cardId = segments[1]
                            val animName = segments[2].removeSuffix(".json")
                            val bundleStr = onGetAnimation(cardId, animName) // Force cache-first validation

                            return if (bundleStr != null) {
                                WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, ByteArrayInputStream(bundleStr.toByteArray(Charsets.UTF_8)))
                            } else {
                                WebResourceResponse("application/json", "UTF-8", 404, "Not Found", corsHeaders, ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8)))
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            loadUrl("https://cardvision.local/index.html")
        }

        root = FrameLayout(this)
        val lp = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        root.addView(previewView, lp)
        root.addView(renderView, lp)
        root.addView(webView, lp)
        root.addView(floatingExperience, lp)
        setContentView(root)

        bgExecutor.execute { syncCatalog() }
    }

    private fun getOfflineRender(id: String): ByteArray? {
        val diskCache = File(renderCacheDir, "$id.png")
        if (diskCache.exists()) return diskCache.readBytes()

        vaultStore.getRender(id)?.let { return it }

        val seenFile = File(File(seenDir, id), "render.png")
        if (seenFile.exists()) return seenFile.readBytes()

        try {
            val f = File(File(filesDir, "vault"), "$id/render.png")
            if (f.exists()) return f.readBytes()
        } catch (_: Exception) {}

        return null
    }

    private fun syncCatalog() {
        try {
            val cat = CardCatalog(this, serverUrl)
            var loaded = try {
                cat.sync()
            } catch (e: Exception) {
                emptyList()
            }

            if (loaded.isEmpty()) {
                val offlineCards = mutableListOf<CardCatalog.LoadedCard>()
                val ownedSet = mutableSetOf<String>()

                val ownedArray = vaultStore.listCards()
                for (i in 0 until ownedArray.length()) {
                    val obj = ownedArray.getJSONObject(i)
                    val id = obj.optString("id")
                    val name = obj.optString("name")
                    ownedSet.add(id)
                    val refBytes = vaultStore.getReference(id)
                    if (refBytes != null) {
                        val mat = Imgcodecs.imdecode(MatOfByte(*refBytes), Imgcodecs.IMREAD_GRAYSCALE)
                        if (!mat.empty()) {
                            offlineCards.add(CardCatalog.LoadedCard(id, name, obj.optString("rarity"), obj.optString("description"), mat))
                        }
                    }
                }

                val seenIds = getSeenCardIds()
                for (id in seenIds) {
                    if (ownedSet.contains(id)) continue
                    val refFile = File(File(seenDir, id), "reference.jpg")
                    if (refFile.exists()) {
                        val mat = Imgcodecs.imread(refFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
                        if (!mat.empty()) {
                            val metaFile = File(File(seenDir, id), "meta.json")
                            val meta = if (metaFile.exists()) JSONObject(metaFile.readText()) else JSONObject()
                            offlineCards.add(CardCatalog.LoadedCard(id, meta.optString("name", id), meta.optString("rarity"), meta.optString("description"), mat))
                        }
                    }
                }

                loaded = offlineCards
                serverStatus = "offline"
            } else {
                serverStatus = if (cat.lastSyncWasOnline) "online" else "offline"
            }

            if (loaded.isNotEmpty()) {
                engine = CardEngine(loaded)
                catalog = cat
                metaById.clear()
                for (c in loaded) metaById[c.id] = c

                if (serverStatus == "online") {
                    backfillVault()
                }
            } else {
                engine = null
            }

            bgExecutor.execute {
                val ownedArray = vaultStore.listCards()
                for (i in 0 until ownedArray.length()) {
                    val id = ownedArray.getJSONObject(i).optString("id")
                    vaultStore.getRender(id)?.let { File(renderCacheDir, "$id.png").writeBytes(it) }
                    val anims = vaultStore.listAnimations(id)
                    for (anim in anims) {
                        vaultStore.getAnimation(id, anim)?.let { File(animCacheDir, "$id-$anim.json").writeText(it) }
                    }
                }
            }
        } catch (e: Exception) {
            serverStatus = "offline"
        }
    }

    private fun markCardAsSeen(cardId: String) {
        val seen = prefs.getStringSet("seen_cards", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (seen.add(cardId)) {
            prefs.edit().putStringSet("seen_cards", seen).apply()
        }
    }

    private fun getSeenCardIds(): Set<String> {
        return prefs.getStringSet("seen_cards", emptySet())?.toSet() ?: emptySet()
    }

    private fun beginScan() {
        if (shuttingDown || !scanRequested || isScanning) return
        isScanning = true
        val generation = ++scanGeneration
        previewView.visibility = View.VISIBLE

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (shuttingDown || !scanRequested || !isScanning || generation != scanGeneration || isFinishing || isDestroyed) {
                return@addListener
            }

            try {
                val provider = future.get()
                if (shuttingDown || !scanRequested || !isScanning || generation != scanGeneration) {
                    return@addListener
                }

                cameraProvider = provider
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

                provider.unbindAll()
                if (shuttingDown || !scanRequested || !isScanning || generation != scanGeneration) {
                    provider.unbindAll()
                    return@addListener
                }

                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                camera?.cameraControl?.enableTorch(torchOn)
            } catch (e: Exception) {
                isScanning = false
                previewView.visibility = View.GONE
                if (!shuttingDown) Log.e("CardVision", "Camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun endScan() {
        scanGeneration++
        isScanning = false
        previewView.visibility = View.GONE
        renderView.visibility = View.GONE
        camera = null
        torchOn = false
        lastSentCornerKey = ""
        lastShownCardId = null
        lastRequestedRenderId = null
        lastPreloadedAnimsForCard = null
        hologramActive = false

        try { cameraProvider?.unbindAll() } catch (_: Exception) {}

        runOnUiThread {
            if (!isDestroyed) {
                webView.evaluateJavascript("window.CVOverlay && window.CVOverlay.hideCard();", null)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(image: ImageProxy) {
        val eng = engine
        if (eng == null) {
            image.close()
            return
        }

        var openCvCrashed = false

        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val buf = plane.buffer
            val stride = plane.rowStride
            if (bytes.size != w * h) bytes = ByteArray(w * h)
            if (stride == w) buf.get(bytes, 0, w * h)
            else for (r in 0 until h) { buf.position(r * stride); buf.get(bytes, r * w, w) }
            raw.create(h, w, CvType.CV_8UC1)
            raw.put(0, 0, bytes)

            when (image.imageInfo.rotationDegrees) {
                90 -> Core.rotate(raw, upright, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(raw, upright, Core.ROTATE_180)
                270 -> Core.rotate(raw, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> raw.copyTo(upright)
            }

            val result = eng.process(upright)

            val corners = result.corners
            if (corners != null) {
                val key = corners.joinToString(",")
                if (key != lastSentCornerKey) {
                    lastSentCornerKey = key
                    runOnUiThread {
                        if (!hologramActive) {
                            renderView.visibility = View.VISIBLE
                            renderView.setQuad(corners, upright.cols(), upright.rows())
                        }
                        sendCornersToJs(corners, upright.cols(), upright.rows())
                    }
                }
            } else {
                if (lastSentCornerKey.isNotEmpty()) {
                    lastSentCornerKey = ""
                    runOnUiThread {
                        renderView.setQuad(null, upright.cols(), upright.rows())
                        webView.evaluateJavascript("window.CVOverlay && window.CVOverlay.update(null);", null)
                    }
                }
            }

            val id = result.cardId
            if (id != null && id != lastShownCardId) {
                lastShownCardId = id
                SoundManager.playScan()
                markCardAsSeen(id)

                val m = metaById[id]
                val inVault = vault.isUnlocked(id)
                val isSeen = !inVault

                runOnUiThread {
                    val json = JSONObject()
                        .put("id", id)
                        .put("name", m?.name ?: id)
                        .put("rarity", m?.rarity ?: "")
                        .put("description", m?.description ?: "")
                        .put("inVault", inVault)
                        .put("isOwned", inVault)
                        .put("isSeen", isSeen)
                    webView.evaluateJavascript("window.CVOverlay && window.CVOverlay.showCard($json);", null)
                }

                if (id != lastRequestedRenderId) {
                    lastRequestedRenderId = id
                    if (!shuttingDown && !bgExecutor.isShutdown) {
                        try {
                            bgExecutor.execute {
                                val cardSeenDir = File(seenDir, id).apply { mkdirs() }

                                var renderBytes = getOfflineRender(id)
                                if (renderBytes == null) {
                                    renderBytes = catalog?.fetchRenderBytes(id)
                                    if (renderBytes != null) {
                                        File(cardSeenDir, "render.png").writeBytes(renderBytes)
                                    }
                                }

                                val refFile = File(cardSeenDir, "reference.jpg")
                                if (!refFile.exists()) {
                                    catalog?.fetchReferenceBytes(id)?.let { refFile.writeBytes(it) }
                                }

                                val metaFile = File(cardSeenDir, "meta.json")
                                if (!metaFile.exists()) {
                                    val json = JSONObject().apply {
                                        put("id", id)
                                        put("name", m?.name ?: id)
                                        put("rarity", m?.rarity ?: "")
                                        put("description", m?.description ?: "")
                                    }
                                    metaFile.writeText(json.toString())
                                }

                                if (renderBytes != null) {
                                    val bmp = BitmapFactory.decodeByteArray(renderBytes, 0, renderBytes.size)
                                    val renderUrl = "https://cardvision.local/render/$id"
                                    runOnUiThread {
                                        if (bmp != null) renderView.setRender(bmp)
                                        webView.evaluateJavascript("window.CVOverlay && window.CVOverlay.setRenderData('${renderUrl}');", null)
                                    }
                                }
                            }
                        } catch (_: RejectedExecutionException) {}
                    }
                }

                if (id != lastPreloadedAnimsForCard) {
                    lastPreloadedAnimsForCard = id
                    loadAnimationsForCard(id)
                }
            } else if (id == null && lastShownCardId != null) {
                lastShownCardId = null
                runOnUiThread { webView.evaluateJavascript("window.CVOverlay && window.CVOverlay.hideCard();", null) }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            openCvCrashed = true
        }

        if (openCvCrashed) {
            image.close()
            return
        }

        val mediaImage = image.image
        if (mediaImage != null) {
            try {
                val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
                scanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            val rawValue = barcode.rawValue ?: continue
                            val isCardVisionUrl = rawValue.startsWith("cardvision://claim") || rawValue.startsWith("cardvision://transfer")
                            if (isCardVisionUrl) {
                                handleQrClaim(rawValue)
                            } else {
                                handleGenericTarget(rawValue)
                            }
                        }
                    }
                    .addOnFailureListener { }
                    .addOnCompleteListener { image.close() }
            } catch (e: Exception) {
                e.printStackTrace()
                image.close()
            }
        } else {
            image.close()
        }
    }

    private fun isValidAnimationBundle(bundle: String): Boolean {
        return try {
            val trimmed = bundle.trim()
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed).length() > 0
            } else {
                val obj = JSONObject(trimmed)
                val frames = obj.optJSONArray("frames")
                frames != null && frames.length() > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun loadAnimationsForCard(cardId: String) {
        if (shuttingDown || bgExecutor.isShutdown) return

        try {
            bgExecutor.execute {
                try {
                    Log.d("CARD_ANIM", "Loading card=$cardId")

                    val networkNames = try { catalog?.fetchAnimationNames(cardId) ?: emptyList() } catch (_: Exception) { emptyList() }
                    val localNames = vaultStore.listAnimations(cardId)
                    val sessionFiles = animCacheDir.listFiles { _, name -> name.startsWith("$cardId-") && name.endsWith(".json") }
                    val sessionNames = sessionFiles?.map { it.name.removePrefix("$cardId-").removeSuffix(".json") } ?: emptyList()

                    val canonicalNames = (networkNames + localNames + sessionNames).distinct()
                    Log.d("CARD_ANIM", "Canonical names=$canonicalNames")

                    if (canonicalNames.isEmpty()) {
                        return@execute
                    }

                    for (name in canonicalNames) {

                        val cacheKey = "$cardId-$name.json"
                        val diskFile = File(animCacheDir, cacheKey)

                        // 1. Session/Disk Cache
                        if (diskFile.exists() && diskFile.length() > 0L) {
                            val bundle = try { diskFile.readText() } catch (_: Exception) { null }

                            if (!bundle.isNullOrBlank() && isValidAnimationBundle(bundle)) {
                                Log.d("CARD_ANIM", "Cache hit card=$cardId anim=$name")

                                // Claim-after-seen cache promotion
                                if (vault.isUnlocked(cardId) && vaultStore.getAnimation(cardId, name).isNullOrBlank()) {
                                    try {
                                        vaultStore.saveAnimation(cardId, name, bundle)
                                        Log.d("CARD_ANIM", "Promoted cache -> VaultStore card=$cardId anim=$name")
                                    } catch (e: Exception) {
                                        Log.e("CARD_ANIM", "Failed promoting animation", e)
                                    }
                                }

                                injectAnimation(cardId, name,bundle)
                                continue
                            }
                        }

                        // 2. Permanent VaultStore Cache
                        val stored = vaultStore.getAnimation(cardId, name)
                        if (!stored.isNullOrBlank() && isValidAnimationBundle(stored)) {
                            Log.d("CARD_ANIM", "VaultStore hit card=$cardId anim=$name")

                            try {
                                diskFile.parentFile?.mkdirs()
                                diskFile.writeText(stored)
                            } catch (_: Exception) {}

                            injectAnimation(cardId, name, stored)
                            continue
                        }

                        // 3. Network Fetch
                        Log.d("CARD_ANIM", "Fetching card=$cardId anim=$name")
                        val bundle = try { catalog?.fetchAnimationBundle(cardId, name) } catch (_: Exception) { null }

                        if (bundle.isNullOrBlank()) continue

                        Log.d("CARD_ANIM", "Bundle length=${bundle.length}")

                        if (!isValidAnimationBundle(bundle)) {
                            Log.d("CARD_ANIM", "Invalid animation bundle card=$cardId anim=$name")
                            continue
                        }

                        try {
                            diskFile.parentFile?.mkdirs()
                            diskFile.writeText(bundle)
                        } catch (_: Exception) {}

                        if (vault.isUnlocked(cardId)) {
                            try { vaultStore.saveAnimation(cardId, name, bundle) } catch (_: Exception) {}
                        }

                        Log.d("CARD_ANIM", "Injecting card=$cardId anim=$name")
                        injectAnimation(cardId, name, bundle)
                    }

                } catch (_: Exception) {}
            }
        } catch (_: RejectedExecutionException) {}
    }

    private fun injectAnimation(
        cardId: String,
        name: String,
        bundle: String
    ) {
        runOnUiThread {
            if (shuttingDown || isFinishing || isDestroyed) return@runOnUiThread

            webView.evaluateJavascript(
                "window.CVOverlay && window.CVOverlay.registerAnimation(" +
                        "${JSONObject.quote(cardId)}," +
                        "${JSONObject.quote(name)}," +
                        bundle +
                        ");",
                null
            )
        }
    }

    private fun processNewClaim(cardId: String, isTransfer: Boolean) {
        val isNew = vault.unlockCard(cardId)
        if (isNew) {
            SoundManager.playClaim()
            val toastMsg = if (isTransfer) "TRADE RECEIVED: $cardId ADDED TO VAULT!" else "NEW CLAIM: $cardId SAVED TO VAULT!"
            runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('$toastMsg');", null) }

            if (!shuttingDown && !bgExecutor.isShutdown) {
                try {
                    bgExecutor.execute {
                        val meta = metaById[cardId]

                        var render = getOfflineRender(cardId)
                        if (render == null || render.isEmpty()) render = catalog?.fetchRenderBytes(cardId) ?: ByteArray(0)

                        var reference = ByteArray(0)
                        val seenRef = File(File(seenDir, cardId), "reference.jpg")
                        if (seenRef.exists()) {
                            reference = seenRef.readBytes()
                        }
                        if (reference.isEmpty()) {
                            reference = catalog?.fetchReferenceBytes(cardId) ?: ByteArray(0)
                        }

                        if (render.isNotEmpty() && reference.isNotEmpty()) {
                            vaultStore.saveCard(
                                cardId,
                                meta?.name ?: cardId,
                                meta?.rarity ?: "",
                                meta?.description ?: "",
                                render,
                                reference
                            )

                            val cardDir = File(File(filesDir, "vault"), cardId).apply { mkdirs() }
                            File(cardDir, "reference.jpg").writeBytes(reference)
                            val json = JSONObject().apply {
                                put("id", cardId)
                                put("name", meta?.name ?: cardId)
                                put("rarity", meta?.rarity ?: "")
                                put("description", meta?.description ?: "")
                            }
                            File(cardDir, "meta.json").writeText(json.toString())

                            loadAnimationsForCard(cardId)
                            runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.refreshVault();", null) }
                        } else {
                            Log.e("CardVision", "Failed to retrieve permanent assets for claim $cardId")
                        }
                    }
                } catch (_: RejectedExecutionException) {}
            }
        }
    }

    private fun handleQrClaim(url: String) {
        if (!processingQrs.add(url)) return

        try {
            val uri = Uri.parse(url)
            val isTransfer = uri.authority == "transfer" || url.startsWith("cardvision://transfer")
            val cardId = uri.getQueryParameter("id") ?: return

            if (vault.isUnlocked(cardId)) {
                runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('Card already in vault!');", null) }
                return
            }

            if (shuttingDown || bgExecutor.isShutdown) return

            if (isTransfer) {
                val token = uri.getQueryParameter("token")
                if (token.isNullOrBlank()) {
                    processingQrs.remove(url)
                    return
                }

                try {
                    bgExecutor.execute {
                        try {
                            val json = JSONObject().put("token", token).toString()
                            val req = Request.Builder()
                                .url("$serverUrl/trade/claim")
                                .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                                .build()
                            httpClient.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    processNewClaim(cardId, true)
                                } else {
                                    processingQrs.remove(url)
                                    runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('Trade Failed: QR is invalid or expired.');", null) }
                                }
                            }
                        } catch(e: Exception) {
                            processingQrs.remove(url)
                            runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('Trade Error: Network unreachable.');", null) }
                        }
                    }
                } catch (_: RejectedExecutionException) {}
                return
            } else {
                val secret = uri.getQueryParameter("secret")
                if (secret.isNullOrBlank()) {
                    processingQrs.remove(url)
                    runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('Invalid QR Format (Missing Secret).');", null) }
                    return
                }

                try {
                    bgExecutor.execute {
                        try {
                            val json = JSONObject().put("card_id", cardId).put("secret", secret).toString()
                            val req = Request.Builder()
                                .url("$serverUrl/claim/physical")
                                .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                                .build()
                            httpClient.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    processNewClaim(cardId, false)
                                } else {
                                    processingQrs.remove(url)
                                    runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('QR Already Claimed or Invalid.');", null) }
                                }
                            }
                        } catch(e: Exception) {
                            processingQrs.remove(url)
                            runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('Network error claiming card.');", null) }
                        }
                    }
                } catch (_: RejectedExecutionException) {}
            }
        } catch (e: Exception) {
            processingQrs.remove(url)
        }
    }
    private val processingTargets = mutableSetOf<String>()

    private fun handleGenericTarget(rawValue: String) {
        if (!processingTargets.add(rawValue)) return
        if (shuttingDown || bgExecutor.isShutdown) { processingTargets.remove(rawValue); return }

        try {
            bgExecutor.execute {
                try {
                    val experience = experienceResolver.resolve(rawValue)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            floatingExperience.show(experience)
                        }
                    }
                } finally {
                    processingTargets.remove(rawValue)
                }
            }
        } catch (_: RejectedExecutionException) {
            processingTargets.remove(rawValue)
        }
    }


    private fun sendCornersToJs(corners: FloatArray, fw: Int, fh: Int) {
        val vw = previewView.width.toFloat()
        val vh = previewView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val s = maxOf(vw / fw, vh / fh)
        val ox = (vw - fw * s) / 2f
        val oy = (vh - fh * s) / 2f

        val arr = JSONArray()
        for (i in 0 until 4) {
            val x = (corners[2 * i] * s + ox) / vw
            val y = (corners[2 * i + 1] * s + oy) / vh
            arr.put(JSONObject().put("x", x).put("y", y))
        }
        webView.evaluateJavascript("window.CVOverlay && window.CVOverlay.update($arr);", null)
    }
    private fun getOfflineAnimation(cardId: String, animName: String): String? {
        val diskFile = File(animCacheDir, "$cardId-$animName.json")

        // 1. Try session/disk cache first
        if (diskFile.exists() && diskFile.length() > 0L) {
            try {
                val bundle = diskFile.readText()

                if (bundle.isNotBlank() && isValidAnimationBundle(bundle)) {
                    return bundle
                }
            } catch (_: Exception) {
            }
        }

        // 2. Fall back to permanent VaultStore
        return try {
            val stored = vaultStore.getAnimation(cardId, animName)

            if (!stored.isNullOrBlank() && isValidAnimationBundle(stored)) {
                stored
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun backfillVault() {
        val unlocked = vault.allUnlockedIds()
        val cat = catalog ?: return

        for (id in unlocked) {
            try {
                val meta = metaById[id]
                val render = cat.fetchRenderBytes(id) ?: ByteArray(0)
                val reference = cat.fetchReferenceBytes(id) ?: ByteArray(0)

                if (render.isNotEmpty() && reference.isNotEmpty()) {
                    vaultStore.saveCard(
                        id,
                        meta?.name ?: id,
                        meta?.rarity ?: "",
                        meta?.description ?: "",
                        render,
                        reference
                    )
                }

                val cardDir = File(File(filesDir, "vault"), id).apply { mkdirs() }

                if (reference.isNotEmpty()) {
                    File(cardDir, "reference.jpg").writeBytes(reference)
                }

                if (render.isNotEmpty()) {
                    File(cardDir, "render.png").writeBytes(render)
                }

                val json = JSONObject().apply {
                    put("id", id)
                    put("name", meta?.name ?: id)
                    put("rarity", meta?.rarity ?: "")
                    put("description", meta?.description ?: "")
                }

                File(cardDir, "meta.json").writeText(json.toString())

                for (name in cat.fetchAnimationNames(id)) {
                    val bundle = getOfflineAnimation(id, name)
                        ?: cat.fetchAnimationBundle(id, name)
                        ?: continue

                    if (!isValidAnimationBundle(bundle)) {
                        Log.w(
                            "CardVision",
                            "Skipping invalid animation bundle for $id/$name"
                        )
                        continue
                    }

                    File(animCacheDir, "$id-$name.json").writeText(bundle)
                    vaultStore.saveAnimation(id, name, bundle)
                }
            } catch (e: Exception) {
                Log.e("CardVision", "Backfill failed for $id", e)
            }
        }
    }

    private fun renderDataUri(id: String): String {
        return try {
            val bytes = getOfflineRender(id)
            if (bytes != null && bytes.isNotEmpty()) {
                "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else ""
        } catch (_: Exception) {
            ""
        }
    }

    override fun onGetVaultCards(): String {
        val ids = LinkedHashSet<String>()

        ids.addAll(vault.allUnlockedIds())

        val seenIds = getSeenCardIds()
        ids.addAll(seenIds)

        val stored = vaultStore.listCards()
        val storedMap = HashMap<String, JSONObject>()
        for (i in 0 until stored.length()) {
            val obj = stored.getJSONObject(i)
            val id = obj.optString("id")
            if (id.isNotBlank()) {
                ids.add(id)
                storedMap[id] = obj
            }
        }

        val out = JSONArray()
        for (id in ids) {
            val mem = metaById[id]
            val db = storedMap[id]
            val owned = vault.isUnlocked(id)
            val seen = !owned && seenIds.contains(id)

            val obj = JSONObject()
                .put("id", id)
                .put("name", mem?.name ?: db?.optString("name", id) ?: id)
                .put("rarity", mem?.rarity ?: db?.optString("rarity", "") ?: "")
                .put("description", mem?.description ?: db?.optString("description", "") ?: "")
                .put("isOwned", owned)
                .put("isSeen", seen)
                .put("renderDataUri", renderDataUri(id))

            out.put(obj)
        }
        return out.toString()
    }

    override fun onGetServerUrl(): String = serverUrl

    override fun onSetServerUrl(url: String) {
        var trimmed = url.trim()
        if (trimmed.isEmpty()) return
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) trimmed = "http://$trimmed"
        serverUrl = trimmed
        prefs.edit().putString("serverUrl", trimmed).apply()
        if (!shuttingDown && !bgExecutor.isShutdown) {
            try {
                bgExecutor.execute { syncCatalog() }
            } catch (_: RejectedExecutionException) {}
        }
    }

    override fun onGetServerStatus(): String = serverStatus

    override fun onStartScan() {
        scanRequested = true
        runOnUiThread {
            if (shuttingDown || isFinishing || isDestroyed) return@runOnUiThread
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                beginScan()
            } else {
                askCamera.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onStopScan() {
        scanRequested = false
        runOnUiThread { endScan() }
    }

    override fun onToggleFlashlight() {
        runOnUiThread {
            val cam = camera ?: return@runOnUiThread
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
        }
    }

    override fun onHologramState(active: Boolean) {
        runOnUiThread {
            hologramActive = active
            if (active) renderView.visibility = View.GONE
            else if (lastSentCornerKey.isNotEmpty()) renderView.visibility = View.VISIBLE
        }
    }

    override fun onInitiateTrade(cardId: String) {
        if (!vault.isUnlocked(cardId)) return
        if (shuttingDown || bgExecutor.isShutdown) return

        val token = UUID.randomUUID().toString()
        val payload = "cardvision://transfer?id=$cardId&token=$token"

        try {
            bgExecutor.execute {
                try {
                    val json = JSONObject().put("token", token).put("card_id", cardId).toString()
                    val req = Request.Builder()
                        .url("$serverUrl/trade/create")
                        .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()

                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            vault.removeCard(cardId)
                            vaultStore.deleteCard(cardId)
                            File(renderCacheDir, "$cardId.png").delete()

                            activeTradeCardId = cardId
                            activeTradeToken = token

                            val size = 512
                            val matrix: BitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
                            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
                            for (x in 0 until size) {
                                for (y in 0 until size) { bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE) }
                            }
                            val out = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            val b64 = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

                            runOnUiThread {
                                webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showTradeModal('${b64}');", null)
                                webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.refreshVault();", null)
                            }
                        } else {
                            runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('Server rejected trade creation.');", null) }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('Network error creating trade.');", null) }
                }
            }
        } catch (_: RejectedExecutionException) {}
    }

    override fun onCancelTrade() {
        val cardId = activeTradeCardId ?: return
        val token = activeTradeToken ?: return
        if (shuttingDown || bgExecutor.isShutdown) return

        try {
            bgExecutor.execute {
                try {
                    val json = JSONObject().put("token", token).toString()
                    val req = Request.Builder()
                        .url("$serverUrl/trade/cancel")
                        .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            processNewClaim(cardId, false)
                            runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('Trade Cancelled. Card returned.');", null) }
                        } else {
                            runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('Too late! Card was already claimed.');", null) }
                        }
                    }
                } catch(e: Exception) {
                    runOnUiThread { webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.showToast('Network error cancelling trade.');", null) }
                }
            }
        } catch (_: RejectedExecutionException) {}
        activeTradeCardId = null
        activeTradeToken = null
    }

    override fun onGetAnimation(cardId: String, animName: String): String? {
        val file = File(animCacheDir, "$cardId-$animName.json")

        if (file.exists() && file.length() > 0L) {
            try {
                val bundle = file.readText()
                if (bundle.isNotBlank() && isValidAnimationBundle(bundle)) {
                    return bundle
                }
            } catch (_: Exception) {}
        }

        try {
            val stored = vaultStore.getAnimation(cardId, animName)
            if (!stored.isNullOrBlank() && isValidAnimationBundle(stored)) {
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(stored)
                } catch (_: Exception) {}
                return stored
            }
        } catch (_: Exception) {}

        return null
    }

    override fun onPlayClickSound() {
        SoundManager.playClick()
    }

    override fun onTestConnection() {
        if (shuttingDown || bgExecutor.isShutdown) return

        try {
            bgExecutor.execute {
                try {
                    val req = Request.Builder().url("$serverUrl/cards").build()
                    httpClient.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) {
                            serverStatus = "offline"
                            runOnUiThread {
                                webView.evaluateJavascript(
                                    "window.CardVision && window.CardVision._connResult(false, 'Failed')",
                                    null
                                )
                            }
                            return@execute
                        }

                        serverStatus = "online"
                        val body = response.body?.string().orEmpty()
                        val count = try { JSONArray(body).length() } catch (_: Exception) { 0 }
                        val msg = JSONObject.quote("Connected â€” $count card${if (count == 1) "" else "s"} on server")
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "window.CardVision && window.CardVision._connResult(true, $msg)",
                                null
                            )
                        }
                    }
                } catch (_: Exception) {
                    serverStatus = "offline"
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.CardVision && window.CardVision._connResult(false, 'Failed')",
                            null
                        )
                    }
                }
            }
        } catch (_: RejectedExecutionException) {}
    }

    override fun onClearVault() {
        try {
            vault.clearAll()
            vaultStore.clearAll()
            File(filesDir, "vault").deleteRecursively()
            File(filesDir, "seen").deleteRecursively()
            File(cacheDir, "renders").deleteRecursively()
            File(cacheDir, "anims").deleteRecursively()
            prefs.edit().remove("seen_cards").apply()
        } catch (e: Exception) {
            Log.e("CardVision", "Clear vault failed", e)
        }

        runOnUiThread {
            webView.evaluateJavascript(
                "if(window.CardVisionUI) window.CardVisionUI.refreshVault();",
                null
            )
        }
    }

    override fun onDestroy() {
        shuttingDown = true
        scanRequested = false
        scanGeneration++

        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        camera = null
        try { scanner.close() } catch (_: Exception) {}
        try { analysisExecutor.shutdownNow() } catch (_: Exception) {}
        try { bgExecutor.shutdownNow() } catch (_: Exception) {}
        try { SoundManager.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
