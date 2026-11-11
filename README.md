# Anki Heatmap

A GitHub-style home-screen heatmap of your AnkiDroid reviews.

- **Anki heatmap** widget: resizable grid. Tap a square for that day's details, ↻ to refresh, the title to open the app, the bottom line to open AnkiDroid.
- **Anki today** widget: ring showing today's cards against your daily average, plus your streak.
- App screen: full-year heatmap, stats, 14-day forecast, deck filter, daily goal.

## How it works
It reads a private copy of your AnkiDroid collection (`collection.anki2`) and counts the review log, so the numbers are exact. This needs:
1. The **F-Droid or GitHub** build of AnkiDroid (the Play Store build keeps its data where other apps can't read it), with its folder in shared storage, e.g. `/storage/emulated/0/AnkiDroid`.
2. "All files access" for Anki Heatmap (read-only use; it never writes to AnkiDroid's files).

## Install
Every push to `main` builds `AnkiHeatmap.apk` and attaches it to a new release (see Releases). Open it on your phone to install or update.

The signing key in `app/signing.keystore` is a personal key so updates install over each other; don't reuse it for anything else.
