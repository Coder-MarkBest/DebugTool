# Availability Module Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the visible "general" debug tab with an abstract availability module for product, QA, and engineering users.

**Architecture:** Keep the existing `debugtools-general` Gradle module to avoid project churn, but replace its primary API with `AvailabilityModule`. Built-in checks cover process liveness and network availability; all other checks are externally supplied as generic availability items.

**Tech Stack:** Android Kotlin, existing MVP module pattern, Kotlin coroutines, JUnit/Robolectric where Android context is needed.

## Global Constraints

- UI copy must use "可用性" instead of "通用".
- The module must stay business-agnostic: no voice-assistant-specific terms in public types.
- Built-in checks: timed process liveness and network availability.
- External integrations update availability through abstract item data.
- Preserve the existing Gradle module name `debugtools-general`.

---

### Task 1: Availability Protocol

**Files:**
- Create: `debugtools-general/src/main/kotlin/com/debugtools/general/AvailabilityModels.kt`
- Test: `debugtools-general/src/test/kotlin/com/debugtools/general/AvailabilityModelsTest.kt`

**Interfaces:**
- Produces: `AvailabilityStatus`, `AvailabilityItem`, `AvailabilityItemSource`

- [x] Write failing tests for status severity ordering and item defaults.
- [x] Run `./gradlew :debugtools-general:testDebugUnitTest --tests com.debugtools.general.AvailabilityModelsTest` and verify failure.
- [x] Implement the model types.
- [x] Re-run the test and verify pass.

### Task 2: Availability Module Replacement

**Files:**
- Create: `debugtools-general/src/main/kotlin/com/debugtools/general/AvailabilityModule.kt`
- Modify: `debugtools-general/src/main/kotlin/com/debugtools/general/GeneralModule.kt`
- Delete: `debugtools-general/src/main/kotlin/com/debugtools/general/DiskMonitor.kt`
- Delete: `debugtools-general/src/main/kotlin/com/debugtools/general/GeneralPresenter.kt`
- Delete: `debugtools-general/src/main/kotlin/com/debugtools/general/GeneralView.kt`
- Test: `debugtools-general/src/test/kotlin/com/debugtools/general/AvailabilityModuleOverviewTest.kt`

**Interfaces:**
- Consumes: Task 1 model types, existing `ProcessMonitor`, existing `DefaultNetworkDataSource`.
- Produces: `AvailabilityModule.builder(context)` with `addProcessCheck`, `addNetworkCheck`, and `addExternalSource`.

- [x] Write failing overview/module tests for title, status aggregation, external items, and built-in process check.
- [x] Run targeted tests and verify failure.
- [x] Implement `AvailabilityModule`.
- [x] Keep `GeneralModule` only as a deprecated compatibility wrapper around `AvailabilityModule`.
- [x] Remove the old disk/general presenter implementation from the public module surface.
- [x] Re-run targeted tests and verify pass.

### Task 3: Demo and Documentation

**Files:**
- Modify: `app/src/main/kotlin/com/debugtools/sample/MainActivity.kt`
- Modify: `docs/INTEGRATION.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `AvailabilityModule`

- [x] Replace demo registration and labels with `AvailabilityModule`.
- [x] Add a demo external availability source for permission-style generic checks without voice-specific SDK API.
- [x] Update docs from "general" wording to "availability" wording where relevant.
- [x] Run `./gradlew :app:assembleDebug`.

### Task 4: Full Verification and Commit

**Files:**
- All changed files.

- [x] Run `./gradlew :debugtools-general:testDebugUnitTest`.
- [x] Run `./gradlew testDebugUnitTest :app:assembleDebug`.
- [x] Install and launch demo on the connected emulator.
- [x] Capture a screenshot and inspect the "可用性" tab and overview item.
- [ ] Stage only intended files and commit.
