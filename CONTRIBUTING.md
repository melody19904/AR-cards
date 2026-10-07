# Contributing to CARD VISION

Thank you for contributing to CARD VISION.

## A Note About the Beginning

CARD VISION did not begin as a complete platform. One of the early physical-card prototypes was **Blue Omelett**.

The actual prototype asset is preserved as:

```text
docs/media/Blue Omelett.jpg
```

Blue Omelett was important because it showed that the physical-card concept could become something real.

**That prototype gave the project a reason to keep going.**

The prototype is part of the project's history. Not every early prototype is expected to represent the final visual or technical direction.

## Before Making Changes

Read `README.md`, `docs/ARCHITECTURE.md`, `docs/DATA_STORAGE.md`, `docs/ASSET_PIPELINE.md`, and `docs/KNOWN_ISSUES.md`.

## High-Risk Areas

Extra care is required when changing `MainActivity`, `VaultStore`, `WebAppBridge`, `CameraX`, `CardEngine`, `server.py`, Supabase integration, animation handling, claim flow, trading flow, or WebView UI.

## Regression Prevention

After changes affecting scanning, test camera permission, camera startup/shutdown, card detection, server detection, flashlight, AR overlay, and vault updates.

After changes affecting the vault, test owned cards, seen cards, persistence, render assets, animations, and application restart.

After changes affecting the WebView bridge, test native method invocation, navigation, scanner start/stop, settings, server connection, and vault refresh.

## Animation Data

Animation bundles can be several megabytes. Do not casually move large animation payloads into SQLite. Android SQLite CursorWindow has practical row-size limitations. Large animation data should use file-based caching where appropriate.

## Assets

Card artwork should have predictable naming. Animation frames should use consistent filenames.

Example:

```text
card_005/animations/magic/frame_01.png
card_005/animations/magic/frame_02.png
card_005/animations/magic/meta.json
```

Do not simply change a file extension to convert an image. Use an actual image conversion process.

## Git Branches

Use `main`, `feature/<name>`, `fix/<name>`, `docs/<name>`, or `refactor/<name>`.

## Commit Messages

Prefer `feat:`, `fix:`, `docs:`, `refactor:`, and `chore:` prefixes.

## Pull Request Checklist

- [ ] Code builds
- [ ] Existing functionality was tested
- [ ] Scanner was tested if affected
- [ ] Vault was tested if affected
- [ ] WebView bridge was tested if affected
- [ ] No secrets were committed
- [ ] Documentation was updated when necessary
- [ ] Large assets were handled appropriately
- [ ] No unrelated refactoring was introduced

## Keep Changes Safe

CARD VISION is still evolving. Prefer small, testable changes over large rewrites.

## Thank You

The project exists because experimentation continued after the first prototype. Blue Omelett was one of those early steps. Every useful contribution helps move CARD VISION from prototype toward a complete AR card platform.
