# CARD VISION Asset Pipeline

## Card Artwork

Card artwork should have predictable filenames, stable card IDs, appropriate dimensions, correct image formats, and no accidental extension changes.

## Animation Frames

Animation frames should be numbered consistently:

```text
frame_01.png
frame_02.png
frame_03.png
...
frame_30.png
```

## Metadata

Animations should include metadata when required, for example:

```json
{"fps":15}
```

## Image Conversion

Do not rename `frame.webp` to `frame.png` and assume conversion occurred. Use an actual image conversion process.

## Supabase

Remote assets should follow the project's expected storage paths. Keep naming consistent between local assets, server catalog entries, Supabase storage, card IDs, and animation names.

## Prototype Assets

The Blue Omelett prototype is preserved at `docs/media/Blue Omelett.jpg` because it has historical development significance.
