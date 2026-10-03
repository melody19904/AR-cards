package com.example.cardengine

import android.webkit.JavascriptInterface
import org.json.JSONObject

class WebAppBridge(private val host: Host) {

    interface Host {
        fun onGetVaultCards(): String
        fun onGetServerUrl(): String
        fun onSetServerUrl(url: String)
        fun onTestConnection()
        fun onClearVault()
        fun onGetServerStatus(): String
        fun onStartScan()
        fun onStopScan()
        fun onToggleFlashlight()
        fun onHologramState(active: Boolean)
        fun onGetAnimation(cardId: String, animName: String): String?
        /** Builds a one-time P2P transfer QR (as a data: URI PNG) for a card already owned locally. */
        fun onGenerateTransferQr(cardId: String): String?
        /** Removes a card from this phone's vault once it's been physically traded away. */
        fun onConfirmTransferOut(cardId: String)
    }

    @JavascriptInterface
    fun getVaultCards(): String = host.onGetVaultCards()

    @JavascriptInterface
    fun getServerUrl(): String = host.onGetServerUrl()

    @JavascriptInterface
    fun setServerUrl(url: String) = host.onSetServerUrl(url)
    @JavascriptInterface
    fun getAnimation(cardId: String, animName: String): String? = host.onGetAnimation(cardId, animName)
    @JavascriptInterface
    fun testConnection() = host.onTestConnection()

    @JavascriptInterface
    fun clearVault() = host.onClearVault()

    @JavascriptInterface
    fun getServerStatus(): String = host.onGetServerStatus()

    @JavascriptInterface
    fun startScan() = host.onStartScan()

    @JavascriptInterface
    fun stopScan() = host.onStopScan()

    @JavascriptInterface
    fun toggleFlashlight() = host.onToggleFlashlight()

    @JavascriptInterface
    fun onHologramState(active: Boolean) = host.onHologramState(active)

    @JavascriptInterface
    fun generateTransferQr(cardId: String): String? = host.onGenerateTransferQr(cardId)

    @JavascriptInterface
    fun confirmTransferOut(cardId: String) = host.onConfirmTransferOut(cardId)

    companion object {
        fun connectionResultJs(ok: Boolean, msg: String): String {
            return "window.CardVision._connResult($ok, ${JSONObject.quote(msg)})"
        }
    }
}