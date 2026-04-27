# KeeNotes App Store Screenshots

This folder contains a reproducible generator for the English App Store marketing screenshots used by the iOS app.

## Source Assets

- Raw 6.9" captures: `../../archives/ios-screenshots/iphone-6.9/`
- App icon: `../KeeNotes/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon~ios-marketing.png`

## Output

Generated screenshots are written to:

- `output/iphone-6.9/`

The generator also creates a quick review contact sheet:

- `output/review-sheet.jpg`

## Current Slide Copy

1. `Capture thoughts` / `before they fade`
2. `Review your days` / `at a glance`
3. `Find old notes` / `in seconds`
4. `Private notes.` / `By design.`

## Regenerate

```bash
./generate.sh
```

## Notes

- The output size is fixed at `1320x2868`, which matches the App Store 6.9" requirement.
- The generator uses local macOS fonts and `ImageMagick`, so it does not require a browser or a network download.
