# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**DebugTools** — Android 平台语音助手 Debug 工具 SDK，以悬浮窗形式展示在所有 App 最上层，支持业务方自由扩展模块。

## Build & Test

```bash
# Build all modules
./gradlew assembleDebug

# Run all tests
./gradlew test

# Run a single module's tests
./gradlew :debugtools-core:test
./gradlew :debugtools-network:test
./gradlew :debugtools-timeline:test
./gradlew :debugtools-general:test

# Build a single module
./gradlew :debugtools-core:assembleDebug
```

**JDK**: Gradle is pinned to Zulu 17 via `gradle.properties` (`org.gradle.java.home`). The system JBR 21 is incompatible with AGP 8.2 jlink usage.

## Architecture

Four Gradle library modules:

```
debugtools-core          ← mandatory, all others depend on this
debugtools-network       ← optional: visible network tab; can host OkHttp capture
debugtools-timeline      ← optional: event timeline with detail expand
debugtools-general       ← optional: availability checks; public entry point is AvailabilityModule
```

### Key design patterns

**MVP throughout**: Each module's tab has `Module → Presenter → View` separation. Presenters hold `CoroutineScope`, cancel on `onDetach()`. Data work on `Dispatchers.IO`, View updates on main thread.

**Module registration**: `DebugTools.builder(context).register(MyModule()).build()`. Each module's `onAttach(context, storage)` receives a `ScopedStorage` that auto-prefixes all keys with `"moduleId/"`.

**Process modes**:
- `ATTACHED` (default): debug tool runs in main process, window managed directly
- `INDEPENDENT`: debug tool runs in `:debug` process; main process communicates via AIDL (`IDebugToolsService`)

**Display modes**: EXPANDED (right panel) → MINIMIZED (floating button, drag+snap) → BRIEF (compact strip) → back to EXPANDED. Managed by `DisplayModeManager`.

**Settings system**: `SettingItem` sealed class (SingleSelect, MultiSelect, Toggle, EditText, Custom), grouped into `SettingGroup` cards, rendered by `SettingsRenderer`. Defaults written to storage on first render only.

**Persistence**: `SettingsStorage` interface — two built-in impls (`SharedPreferencesStorage` default, `DataStoreStorage`). `ScopedStorage` is internal and wraps any impl with key prefixing.

### Package structure

```
com.debugtools.core
  ├── DebugTools / DebugToolsBuilder    ← public entry points
  ├── ProcessMode                        ← ATTACHED / INDEPENDENT
  ├── persistence/                       ← SettingsStorage + impls
  ├── module/                            ← DebugModule interface, BriefItem
  ├── settings/                          ← SettingItem, SettingGroup, SettingsRenderer
  ├── ipc/                               ← AIDL, DebugToolsService/Client/Controller
  └── window/                            ← DisplayModeManager, FloatingWindowManager, Views
```

### Non-obvious constraints

- `TYPE_APPLICATION_OVERLAY` requires API 26+ and `SYSTEM_ALERT_WINDOW` permission — `FloatingWindowManager.init()` throws `OverlayPermissionException` if not granted.
- `DataStoreStorage.runBlocking` has no explicit dispatcher to avoid IO thread pool exhaustion deadlock.
- `ScopedStorage.clear()` is a no-op — `SettingsStorage` has no key enumeration API.
- AIDL parcelable types require `.aidl` declaration files alongside the interface files in `src/main/aidl/`.
- `debugtools-general` has been replaced conceptually by availability checks; keep API additions business-agnostic (`AvailabilityItem` / `AvailabilityStatus`) and avoid voice-assistant-specific dependency names in SDK types.
- `debugtools-network` is the user-facing Network tab. If OkHttp capture is needed, host `NetworkCaptureModule` through `NetworkModule.builder().capture(capture)` instead of registering a separate capture tab.
- `DebugTools` singleton — calling `build()` twice orphans the old instance (by design for simplicity).
