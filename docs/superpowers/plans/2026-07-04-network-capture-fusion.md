# Network Capture Fusion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the user-facing Network and Network Capture tabs into one Network tab while reusing the existing OkHttp capture implementation.

**Architecture:** `debugtools-network` becomes the visible network entry point and can optionally host a `NetworkCaptureModule`. The capture Gradle module remains physically separate and keeps its interceptors, WebSocket wrapper, repository, UI, and recording export.

**Tech Stack:** Android Kotlin Views, existing `DebugModule`/`OverviewProvider`/`RecordableModule`, Gradle project dependencies, JUnit/Robolectric.

## Global Constraints

- Demo should register only one visible network tab.
- Overview should expose one `网络` item, not separate `网络抓包`.
- Network quality is state-style; capture data is event/list/detail-style.
- Do not rewrite the OkHttp capture internals.
- Preserve the existing capture interceptor and WebSocket APIs for host code.

---

### Task 1: Capture Summary API

**Files:**
- Modify: `debugtools-okhttp-capture/src/main/kotlin/com/debugtools/okhttp/NetworkCaptureModule.kt`
- Test: `debugtools-okhttp-capture/src/test/kotlin/com/debugtools/okhttp/NetworkCaptureModuleOverviewTest.kt`

- [x] Add tests for a public capture summary used by the Network module.
- [x] Run targeted tests and verify the new API is missing.
- [x] Implement `NetworkCaptureSummary` and `captureSummary()`.
- [x] Re-run targeted tests and verify pass.

### Task 2: Network Module Hosts Capture

**Files:**
- Modify: `debugtools-network/build.gradle.kts`
- Modify: `debugtools-network/src/main/kotlin/com/debugtools/network/NetworkModule.kt`
- Test: `debugtools-network/src/test/kotlin/com/debugtools/network/NetworkModuleFusionTest.kt`

- [x] Add tests for builder capture wiring and merged overview status.
- [x] Run targeted tests and verify failure.
- [x] Implement `NetworkModule.builder().capture(capture).build()` and merged UI.
- [x] Delegate recording export to the hosted capture module.
- [x] Re-run targeted tests and verify pass.

### Task 3: Demo and Docs

**Files:**
- Modify: `app/src/main/kotlin/com/debugtools/sample/MainActivity.kt`
- Modify: `docs/INTEGRATION.md`
- Modify: `CLAUDE.md`

- [x] Replace `NetworkModule.create()` demo registration with `NetworkModule.builder().capture(captureModule).build()`.
- [x] Keep demo HTTP/WebSocket generation using the same `captureModule`.
- [x] Update docs to describe one visible Network tab with optional capture.
- [x] Verify app build and emulator screenshot.
