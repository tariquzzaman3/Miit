# Miit

Miit is an Android-first, Material 3 watchface design studio for compatible Xiaomi/MI Band-family wearables.

## Vision

- Detect a connected band's model, region/variant, firmware and display capabilities.
- Read compatible watchface data where the device protocol permits it.
- Keep imported and created watchface projects in local device storage.
- Provide a no-code, drag-and-drop editor for text, images, shapes and data fields.
- Support device fonts, imported fonts and Google Fonts where licensing permits.
- Offer optional AI assistance for design ideas, image/background generation and editing through user-supplied providers.
- Compile a project into the exact payload required by its detected target and install it through the matching device transport.

## Architecture

`core/` contains device-neutral models and interfaces. Each band family should have its own transport and compiler adapter so compatibility can be expanded without changing the editor.

The initial UI is an MVP scaffold. Device communication and watchface compilation are intentionally isolated behind `BandTransport` and `WatchfaceCompiler`; these will be implemented from documented/reverse-engineered open-source protocol and format work, with attribution and license requirements preserved.

## Build on Android/GitHub

GitHub Actions builds the debug APK automatically on pushes to `main` and pull requests. No PC is required to edit the repository; GitHub's browser editor can be used for source changes.

## Roadmap

1. Real Bluetooth discovery and device identification.
2. Per-family authentication/session handling.
3. Watchface read/import and project extraction.
4. Mi-Create-compatible compiler adapters and target capability profiles.
5. Real canvas editor with drag/resize/rotate/layer controls.
6. Unicode font discovery/import and font rendering tests.
7. Local project database and thumbnails.
8. AI provider abstraction with secure local API-key storage.
9. Watchface validation, preview and installation.
10. Compatibility test matrix across band models, regions and firmware versions.

## Important compatibility note

There is no single universal watchface protocol across every Mi Band generation. Miit therefore uses capability detection and adapters rather than assuming one file format works for all devices.
