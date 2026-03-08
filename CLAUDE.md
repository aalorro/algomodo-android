# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run a single unit test class
./gradlew testDebugUnitTest --tests "com.artmondo.algomodo.ExampleUnitTest"

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean

# Lint check
./gradlew lint
```

## Architecture

Single-module Android app (`com.artmondo.algomodo`) using **MVVM + Jetpack Compose + Hilt**.

### Generator System (core abstraction)

The app is a generative art tool built around the `Generator` interface (`generators/Generator.kt`). Each generator implements `renderCanvas()` for bitmap output and optionally `renderVector()` for SVG output. Generators declare their parameters via `parameterSchema` (sealed class `Parameter` with Number, Boolean, Select, Color, Text variants) and are organized into families.

**88+ generators** across 10 families: `animation/`, `cellular/`, `fractals/`, `geometry/`, `graphs/`, `image/`, `noise/`, `plotter/`, `text/`, `voronoi/`.

`GeneratorRegistry` (`core/registry/`) is a singleton that indexes all generators by ID and family. It is populated during `AlgoApp.onCreate()`.

### Data Layer

- **Room** database stores presets (name, generator ID, seed, parameters, palette, thumbnail)
- **DataStore** persists user preferences (theme, quality, animation fps, seed lock)
- **Palettes** (`data/palettes/`): 10 curated color palettes with gradient interpolation

### Recipe System

`RecipeSerializer` (`core/recipe/`) handles JSON serialization of complete render configs (generator ID, seed, parameters, palette, canvas settings, post-FX). Used for sharing/import/export.

### Rendering Pipeline

Generators render to `Canvas`/`Bitmap`. `PostFXProcessor` (`rendering/`) applies optional grain, vignette, dither, posterize effects. `SvgBuilder` produces vector output for supported generators.

### Export

Supports PNG, JPG, GIF (animated), SVG, and MP4 video export. Export logic lives in `export/`.

### ViewModels

- `MainViewModel`: generator selection, parameter state, undo/redo (50-item stack), presets
- `ExportViewModel`: export format settings and progress

### UI

Jetpack Compose with Material 3. Pager-based tab navigation. Key composables in `ui/components/` (CanvasView, GeneratorPicker, ParameterControls, PaletteSelector) and `ui/screens/`.

## Key Technical Details

- **Kotlin DSL** for Gradle, versions managed via `gradle/libs.versions.toml`
- **KSP** (not kapt) for Hilt and Room annotation processing
- **Java 17** source/target compatibility
- **Min SDK 26**, Compile SDK 36
- All source under `app/src/main/java/com/artmondo/algomodo/`
- Portrait-only orientation (`MainActivity`)
- Image input generators use camera/gallery via intent filters
- **Changelog**: `CHANGELOG.md` tracks version history. The in-app changelog dialog (`ui/dialogs/ChangelogDialog.kt`) should be kept in sync with `CHANGELOG.md` when adding new versions.
