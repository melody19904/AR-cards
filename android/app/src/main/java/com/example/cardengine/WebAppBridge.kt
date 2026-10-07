package com.example.cardengine

import android.webkit.JavascriptInterface

class WebAppBridge(private val host: Host) {

    interface Host {
        fun onGetVaultCards(): String
        fun onGetServerUrl(): String
        fun onSetServerUrl(url: String)
        fun onGetServerStatus(): String
        fun onStartScan()
        fun onStopScan()
        fun onToggleFlashlight()
        fun onHologramState(active: Boolean)
        fun onGetAnimation(cardId: String, animName: String): String?
        fun onPlayClickSound()

        // --- Escrow Trade Hooks ---
        fun onInitiateTrade(cardId: String)
        fun onCancelTrade()

        // --- Settings Hooks ---
        fun onTestConnection()
        fun onClearVault()
    }

    @JavascriptInterface fun getVaultCards(): String = host.onGetVaultCards()
    @JavascriptInterface fun getServerUrl(): String = host.onGetServerUrl()
    @JavascriptInterface fun setServerUrl(url: String) = host.onSetServerUrl(url)
    @JavascriptInterface fun getServerStatus(): String = host.onGetServerStatus()
    @JavascriptInterface fun startScan() = host.onStartScan()
    @JavascriptInterface fun stopScan() = host.onStopScan()
    @JavascriptInterface fun toggleFlashlight() = host.onToggleFlashlight()
    @JavascriptInterface fun setHologramState(active: Boolean) = host.onHologramState(active)
    @JavascriptInterface fun getAnimation(cardId: String, animName: String): String? = host.onGetAnimation(cardId, animName)
    @JavascriptInterface fun playClickSound() = host.onPlayClickSound()

    @JavascriptInterface fun initiateTrade(cardId: String) = host.onInitiateTrade(cardId)
    @JavascriptInterface fun cancelTrade() = host.onCancelTrade()

    @JavascriptInterface fun testConnection() = host.onTestConnection()
    @JavascriptInterface fun clearVault() = host.onClearVault()
}