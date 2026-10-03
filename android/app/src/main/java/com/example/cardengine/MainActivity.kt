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
import android.util.Size
import android.view.View
import android.view.ViewGroup
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), WebAppBridge.Host {

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var renderView: RenderOverlayView
    private lateinit var webView: WebView

    private lateinit var vault: VaultHelper
    private lateinit var vaultStore: VaultStore

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private var serverUrl: String = ""
    private var serverStatus: String = "unknown"

    private var catalog: CardCatalog? = null
    private var engine: CardEngine? = null
    private val metaById = HashMap<String, CardCatalog.LoadedCard>()

    // THE RAM CACHE: Dies when the app closes, saving storage
    private val sessionAnimCache = HashMap<String, String>()
    private val sessionRenderCache = HashMap<String, ByteArray>()

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

    private var bytes = ByteArray(0)
    private val raw by lazy { Mat() }
    private val upright by lazy { Mat() }
    private var lastPingMs = 0L
    private val pingIntervalMs = 8000L

    private var lastSentCornerKey: String = ""
    private var lastShownCardId: String? = null
    private var lastRequestedRenderId: String? = null
    private var lastPreloadedAnimsForCard: String? = null

    private var hologramActive = false

    private val scanner by lazy { BarcodeScanning.getClient() }
    private var lastClaimedUrl: String = ""

    private val askCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) beginScan()
            else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppBridge(this@MainActivity), "CardVision")
            loadUrl("file:///android_asset/ui/index.html")
        }

        root = FrameLayout(this)
        val lp = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        root.addView(previewView, lp)
        root.addView(renderView, lp)
        root.addView(webView, lp)
        setContentView(root)

        bgExecutor.execute { syncCatalog() }
    }

    private fun syncCatalog() {
        try {
            val cat = CardCatalog(this, serverUrl)
            val loaded = cat.sync()
            val eng = CardEngine(loaded)

            catalog = cat
            engine = eng
            metaById.clear()
            for (c in loaded) metaById[c.id] = c
            serverStatus = if (cat.lastSyncWasOnline) "online" else "offline"

            backfillVault()

            runOnUiThread {
                webView.evaluateJavascript(
                    "if(window.CardVisionUI) window.CardVisionUI.refreshServerStatus();",
                    null
                )
            }
        } catch (_: Exception) {
            serverStatus = "offline"
            runOnUiThread {
                webView.evaluateJavascript(
                    "if(window.CardVisionUI) window.CardVisionUI.refreshServerStatus();",
                    null
                )
            }
        }
    }

    private fun markCardAsSeen(cardId: String) {
        val seen = prefs.getStringSet("seen_cards", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (seen.add(cardId)) {
            prefs.edit().putStringSet("seen_cards", seen).apply()
        }
    }

    private fun beginScan() {
        if (isScanning) return
        isScanning = true
        previewView.visibility = View.VISIBLE

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun endScan() {
        if (!isScanning) return
        isScanning = false
        previewView.visibility = View.GONE
        renderView.visibility = View.GONE
        cameraProvider?.unbindAll()
        camera = null
        torchOn = false
        lastSentCornerKey = ""
        lastShownCardId = null
        hologramActive = false
        runOnUiThread {
            webView.evaluateJavascript("window.CVOverlay && window.CVOverlay.hideCard();", null)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(image: ImageProxy) {
        val eng = engine
        if (eng == null) {
            image.close()
            return
        }

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
            markCardAsSeen(id)

            val m = metaById[id]
            val inVault = vault.isUnlocked(id)
            runOnUiThread {
                val json = JSONObject()
                    .put("id", id)
                    .put("name", m?.name ?: id)
                    .put("rarity", m?.rarity ?: "")
                    .put("description", m?.description ?: "")
                    .put("inVault", inVault)
                webView.evaluateJavascript(
                    "window.CVOverlay && window.CVOverlay.showCard($json);", null
                )
            }

            if (id != lastRequestedRenderId) {
                lastRequestedRenderId = id
                bgExecutor.execute {
                    // Check the RAM Cache first so popping in and out is instant
                    var renderBytes = sessionRenderCache[id]
                    if (renderBytes == null) {
                        renderBytes = catalog?.fetchRenderBytes(id)
                        if (renderBytes != null) sessionRenderCache[id] = renderBytes
                    }

                    if (renderBytes != null) {
                        val b64 = Base64.encodeToString(renderBytes, Base64.NO_WRAP)
                        val dataUri = "data:image/png;base64,$b64"
                        val bmp = BitmapFactory.decodeByteArray(renderBytes, 0, renderBytes.size)
                        runOnUiThread {
                            if (bmp != null) renderView.setRender(bmp)
                            webView.evaluateJavascript(
                                "window.CVOverlay && window.CVOverlay.setRenderData(${JSONObject.quote(dataUri)});",
                                null
                            )
                        }
                    }
                }
            }

            if (id != lastPreloadedAnimsForCard) {
                lastPreloadedAnimsForCard = id
                loadAnimationsForCard(id)
            }
        } else if (id == null && lastShownCardId != null) {
            lastShownCardId = null
            runOnUiThread {
                webView.evaluateJavascript("window.CVOverlay && window.CVOverlay.hideCard();", null)
            }
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPingMs > pingIntervalMs) {
            lastPingMs = nowMs
            pingServer()
        }

        val mediaImage = image.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue ?: continue
                        val isCardVisionUrl = rawValue.startsWith("cardvision://claim") ||
                                rawValue.startsWith("cardvision://transfer")
                        if (isCardVisionUrl && rawValue != lastClaimedUrl) {
                            lastClaimedUrl = rawValue
                            handleQrClaim(rawValue)
                        }
                    }
                }
                .addOnCompleteListener {
                    image.close()
                }
        } else {
            image.close()
        }
    }

    private fun loadAnimationsForCard(cardId: String) {
        bgExecutor.execute {
            val names = vaultStore.listAnimations(cardId)
            if (names.isNotEmpty()) {
                for (name in names) {
                    val bundle = vaultStore.getAnimation(cardId, name) ?: continue
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.CVOverlay && window.CVOverlay.registerAnimation(" +
                                    "${JSONObject.quote(cardId)}, ${JSONObject.quote(name)}, $bundle);", null
                        )
                    }
                }
            } else {
                val anims = catalog?.fetchAnimationNames(cardId) ?: emptyList()
                var loadedAtLeastOne = false

                for (name in anims) {
                    val cacheKey = "$cardId:$name"
                    val cachedBundle = sessionAnimCache[cacheKey]

                    if (cachedBundle != null) {
                        // Instant hit from the RAM Cache
                        loadedAtLeastOne = true
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "window.CVOverlay && window.CVOverlay.registerAnimation(" +
                                        "${JSONObject.quote(cardId)}, ${JSONObject.quote(name)}, $cachedBundle);", null
                            )
                        }
                    } else {
                        // Not cached yet. Stream from the server and inject into RAM Cache
                        val bundleUrl = "$serverUrl/cards/$cardId/animations/$name/bundle"
                        val request = Request.Builder().url(bundleUrl).build()

                        try {
                            httpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    val bundle = response.body?.string() ?: return@use
                                    loadedAtLeastOne = true
                                    sessionAnimCache[cacheKey] = bundle

                                    if (vault.isUnlocked(cardId)) {
                                        vaultStore.saveAnimation(cardId, name, bundle)
                                    }

                                    runOnUiThread {
                                        webView.evaluateJavascript(
                                            "window.CVOverlay && window.CVOverlay.registerAnimation(" +
                                                    "${JSONObject.quote(cardId)}, ${JSONObject.quote(name)}, $bundle);", null
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                if (!loadedAtLeastOne) {
                    lastPreloadedAnimsForCard = null
                }
            }
        }
    }

    private fun handleQrClaim(url: String) {
        try {
            val uri = Uri.parse(url)
            // "claim" = first scan of the physical/sticker QR. "transfer" = another phone's
            // trade code, generated by onGenerateTransferQr(). Both end up unlocking the card
            // locally the same way; only the toast differs.
            val isTransfer = uri.authority == "transfer" || url.startsWith("cardvision://transfer")
            val cardId = uri.getQueryParameter("id") ?: return

            if (isTransfer) {
                // Malformed/foreign transfer codes are ignored rather than silently claimed.
                val token = uri.getQueryParameter("token")
                if (token.isNullOrBlank()) return
            }

            val isNew = vault.unlockCard(cardId)

            if (isNew) {
                val toastMsg = if (isTransfer)
                    "TRADE RECEIVED: $cardId ADDED TO YOUR VAULT!"
                else
                    "NEW CLAIM: $cardId SAVED TO VAULT!"
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.CardVisionUI) window.CardVisionUI.showToast('$toastMsg');", null
                    )
                }
                bgExecutor.execute {
                    val meta = metaById[cardId]

                    // Pull the render image from the RAM Cache if possible to save it offline instantly
                    val r = sessionRenderCache[cardId] ?: catalog?.fetchRenderBytes(cardId)
                    val ref = catalog?.fetchReferenceBytes(cardId)
                    if (r != null && ref != null) {
                        vaultStore.saveCard(
                            cardId, meta?.name ?: cardId, meta?.rarity ?: "",
                            meta?.description ?: "", r, ref
                        )
                    }

                    // Same for animations: if the user was just looking at the card, they are in RAM. Save them instantly.
                    val anims = catalog?.fetchAnimationNames(cardId) ?: emptyList()
                    for (name in anims) {
                        val cacheKey = "$cardId:$name"
                        val bundle = sessionAnimCache[cacheKey] ?: catalog?.fetchAnimationBundle(cardId, name) ?: continue
                        vaultStore.saveAnimation(cardId, name, bundle)
                    }
                    loadAnimationsForCard(cardId)

                    runOnUiThread {
                        webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.refreshVault();", null)
                    }
                }
            } else {
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.CardVisionUI) window.CardVisionUI.showToast('Card is already in your vault!');", null
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        webView.evaluateJavascript(
            "window.CVOverlay && window.CVOverlay.update($arr);", null
        )
    }

    private fun pingServer() {
        bgExecutor.execute {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/health")
                    .get()
                    .build()

                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        serverStatus = "offline"
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            serverStatus = if (it.isSuccessful) "online" else "offline"
                        }
                    }
                })
            } catch (_: Exception) {
                serverStatus = "offline"
            }
        }
    }

    private fun backfillVault() {
        val unlocked = vault.allUnlockedIds()
        val cat = catalog ?: return
        val canRefreshExisting = cat.lastSyncWasOnline

        for (id in unlocked) {
            val alreadyVaulted = vaultStore.hasCard(id)
            if (!alreadyVaulted || canRefreshExisting) {
                val meta = metaById[id]
                val r = cat.fetchRenderBytes(id)
                val ref = cat.fetchReferenceBytes(id)
                if (r != null) {
                    vaultStore.saveCard(
                        id, meta?.name ?: id, meta?.rarity ?: "",
                        meta?.description ?: "", r, ref
                    )
                } else if (!alreadyVaulted) {
                    continue
                }
            }

            val serverAnims = cat.fetchAnimationNames(id)
            val namesToFetch = if (canRefreshExisting) serverAnims
            else serverAnims.filterNot { it in vaultStore.listAnimations(id) }
            for (name in namesToFetch) {
                val bundle = cat.fetchAnimationBundle(id, name) ?: continue
                vaultStore.saveAnimation(id, name, bundle)
            }
        }
    }

    override fun onGetVaultCards(): String {
        val ownedArray = vaultStore.listCards()
        val ownedIds = mutableSetOf<String>()
        val allCards = JSONArray()

        for (i in 0 until ownedArray.length()) {
            val obj = ownedArray.getJSONObject(i)
            obj.put("isOwned", true)
            ownedIds.add(obj.optString("id"))
            allCards.put(obj)
        }

        val seenIds = prefs.getStringSet("seen_cards", emptySet()) ?: emptySet()
        for (id in seenIds) {
            if (!ownedIds.contains(id)) {
                val meta = metaById[id]
                if (meta != null) {
                    val obj = JSONObject()
                    obj.put("id", id)
                    obj.put("name", meta.name)
                    obj.put("rarity", meta.rarity)
                    obj.put("description", meta.description)
                    obj.put("renderDataUri", "$serverUrl/cards/$id/render.png")
                    obj.put("isOwned", false)
                    allCards.put(obj)
                }
            }
        }
        return allCards.toString()
    }

    override fun onGetServerUrl(): String = serverUrl

    override fun onSetServerUrl(url: String) {
        var trimmed = url.trim()
        if (trimmed.isEmpty()) return

        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }

        serverUrl = trimmed
        prefs.edit().putString("serverUrl", trimmed).apply()
        bgExecutor.execute { syncCatalog() }
    }

    override fun onTestConnection() {
        bgExecutor.execute {
            try {
                val req = Request.Builder().url("$serverUrl/health").build()
                httpClient.newCall(req).execute().use { resp ->
                    val ok = resp.isSuccessful
                    val msg = if (ok) {
                        val body = resp.body?.string() ?: "{}"
                        val n = JSONObject(body).optInt("cards", 0)
                        "Connected - $n card${if (n == 1) "" else "s"} on server"
                    } else "Server returned ${resp.code}"
                    runOnUiThread {
                        webView.evaluateJavascript(WebAppBridge.connectionResultJs(ok, msg), null)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    webView.evaluateJavascript(
                        WebAppBridge.connectionResultJs(false, e.message ?: "Failed"), null
                    )
                }
            }
        }
    }

    override fun onClearVault() {
        vault.clearAll()
        vaultStore.clearAll()
        prefs.edit().remove("seen_cards").apply()
        // Wipes the temporary memory block!
        sessionAnimCache.clear()
        sessionRenderCache.clear()
    }

    override fun onGetServerStatus(): String = serverStatus

    override fun onStartScan() {
        runOnUiThread {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) beginScan()
            else askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStopScan() {
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

    /**
     * Builds the one-time P2P transfer QR for a card already in this phone's vault.
     * The QR encodes cardvision://transfer?id=<cardId>&token=<bearer token>. There is no
     * server involved: whoever's camera reads this payload unlocks the card locally. That
     * also means nothing here can detect or block the same QR being shown to two different
     * phones - the trust boundary is "you physically handed the card over," same as a real
     * paper trading card. See onConfirmTransferOut for the other half of that trust model.
     */
    override fun onGenerateTransferQr(cardId: String): String? {
        if (!vault.isUnlocked(cardId)) return null
        val token = vault.tokenFor(cardId) ?: return null
        val payload = "cardvision://transfer?id=$cardId&token=$token"
        return try {
            val size = 512
            val matrix: BitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Removes the card from THIS phone once the user confirms the trade is done. This is a
     * manual, one-way action - there's no network link back to the receiving phone to confirm
     * it actually scanned successfully, so the UI makes the user explicitly tap "mark as
     * traded" rather than deleting automatically when the QR is shown.
     */
    override fun onConfirmTransferOut(cardId: String) {
        vault.removeCard(cardId)
        vaultStore.deleteCard(cardId)
        sessionRenderCache.remove(cardId)
        runOnUiThread {
            webView.evaluateJavascript("if(window.CardVisionUI) window.CardVisionUI.refreshVault();", null)
        }
    }

    override fun onGetAnimation(cardId: String, animName: String): String? {
        catalog?.fetchAnimationBundle(cardId, animName)?.let { bundle ->
            vaultStore.saveAnimation(cardId, animName, bundle)
            return bundle
        }
        return vaultStore.getAnimation(cardId, animName)
    }

    override fun onDestroy() {
        super.onDestroy()
        endScan()
        bgExecutor.shutdown()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}