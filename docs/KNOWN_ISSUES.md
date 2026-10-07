# Known Issues

## Large Animation Bundles

Large animation bundles can exceed Android SQLite CursorWindow row limits. Animation payloads should therefore be handled through file-based caching rather than oversized SQLite rows.

## WebView Bridge

Android JavaScript bridge methods should be invoked through the injected bridge object. Avoid detaching bridge methods into ordinary JavaScript function references.

## Scanner Regression Risk

Changes to MainActivity, WebView navigation, CameraX lifecycle, permission handling, or the JavaScript bridge can affect scanner startup and shutdown. Test scanning after changes in these areas.

## Backend Availability

The application may depend on remote catalog/backend availability. Offline/local cache behavior should be preferred where possible.

## Prototype Status

CARD VISION remains under active development.
