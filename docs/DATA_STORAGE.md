# CARD VISION Data Storage

## Storage Categories

### Small Metadata

Suitable for structured local storage: card identifiers, ownership state, basic metadata, settings, and timestamps.

### Render Assets

Rendered card images may be cached on disk, for example `render-cache/card_001.png`.

### Animation Assets

Large animation bundles should use file-based storage/caching, for example `animation-cache/card_005-magic.json`.

## Why Large Animation Data Is Different

A card animation can contain dozens of encoded image frames. For example, 29 frames at approximately 74 KB each is approximately 2.1 MB of raw frame data before encoding overhead. Base64 encoding increases the payload further.

A multi-megabyte SQLite row can exceed Android's CursorWindow capacity and result in `SQLiteBlobTooBigException` / `Row too big to fit into CursorWindow`.

Therefore large animation bundles should not be loaded from SQLite as ordinary database rows.

## Storage Rule

Use the database for metadata. Use files for large binary or encoded animation payloads. Do not change the database schema simply to accommodate an oversized animation bundle without architectural review.

## Cache Promotion

```text
Remote animation
       |
       v
Disk animation cache
       |
       +----> WebView
       +----> Local owned-card storage when appropriate
```
