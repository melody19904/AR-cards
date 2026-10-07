# CARD VISION Architecture

## Overview

```text
Physical Card
      |
      v
CameraX Camera
      |
      v
Card Detection Engine
      |
      +----> Recognition
      +----> Card Metadata
                 |
                 v
            MainActivity
                 |
                 v
            WebAppBridge
                 |
                 v
               WebView
          /      |      \
       HOME    SCAN    VAULT
                         |
                      SETTINGS
                 |
                 v
              CVOverlay
```

## Native Layer

The native Android layer handles camera permissions, CameraX, image analysis, flashlight, local persistence, file caching, network access, WebView hosting, and native/JavaScript communication.

## WebView Layer

The WebView provides HOME, SCAN, VAULT, SETTINGS, AR overlay presentation, UI navigation, and trade UI. It should not directly own Android hardware.

## Native JavaScript Bridge

The bridge exposes operations including `startScan()`, `stopScan()`, `toggleFlashlight()`, `getVaultCards()`, `getServerUrl()`, `setServerUrl()`, `getServerStatus()`, `testConnection()`, `clearVault()`, `initiateTrade()`, `cancelTrade()`, and `setHologramState()`.

Bridge calls should be made directly through the injected object.

## Animation Architecture

Preferred:

```text
Native disk cache
       |
       v
https://cardvision.local/anim/...
       |
       v
WebView fetch()
       |
       v
CVOverlay
```

This avoids sending multi-megabyte animation JSON through `evaluateJavascript`.

## Backend

The server provides card catalog data, card metadata, animation names, animation bundles, and remote asset access. Supabase provides persistent backend storage for appropriate assets and metadata.

## Design Principle

Maintain boundaries between hardware, native Android, local storage, network/backend, WebView UI, and AR presentation.
