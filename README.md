# Scann3D - Spatial AR Target & Experience Engine

## 1. What Was Fixed & Upgraded
- **Zero Reload Loop:** `ExperienceResolver` caches resolved targets in memory and `FloatingWebExperienceView` locks onto `currentTargetId`. Holding the camera over a QR code never reloads the WebView; subsequent frames only stream 60 FPS corner coordinates to update the 3D AR pose.
- **Spatial AR Tracking + Auto-Dock:** Draws neon `#22FF88` AR corner brackets and tether lines around the physical QR code and tilts/scales the panel in 3D space. Moving the camera away (> 650ms) or tapping `PIN HUD` smoothly docks the panel flat to the center of the screen.
- **Dismissal Lockout:** Closing the panel (`X`) locks out that target ID until the QR code leaves the camera frame for > 1.5s.
- **Non-Blocking QR Scanner:** `MainActivity.analyze()` no longer exits early when `CardEngine` is null.

## 2. Build & Run Commands
```powershell
$env:JAVA_HOME = "C:\Users\NISCHAY\desktop\ar\android\jbr"
cd C:\Users\NISCHAY\Desktop\ar\Scann3D\CardVisionEngine
.\gradlew.bat assembleDebug
