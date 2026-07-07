# Car Console Layout Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the expanded overlay's top tab bar with a left vertical tab rail, add manual width resizing for the expanded panel, and apply the approved dark car-console visual style.

**Architecture:** Keep existing `DisplayMode` behavior and module APIs. `ExpandedView` owns the vertical tab/content layout and module content switching; `FloatingRootView` owns the resize handle because it has access to the live `WindowManager.LayoutParams`; `DebugToolsTheme` centralizes colors and dimensions used by core overlay views.

**Tech Stack:** Kotlin, Android programmatic Views, Robolectric/JUnit 4.

## Global Constraints

- Do not implement the overview panel in this change.
- Do not enlarge tab controls for driving use; this tool is for product, development, and test debug while parked/in lab conditions.
- Put tabs on the left vertically; panel width can be manually resized and only the function/content area grows.
- Apply this palette: background `#0B1117`, panel `#111A22`, secondary panel `#16212B`, divider `#25313D`, primary text `#F2F5F8`, secondary text `#9AA8B5`, success `#35C46A`, warning `#F6B33B`, danger `#EF4E4E`.

---

## File Structure

- Create `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/DebugToolsTheme.kt`: shared colors and dp helper constants.
- Modify `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/TabBarView.kt`: convert to vertical tab rail.
- Modify `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/ExpandedView.kt`: use horizontal layout with tab rail on the left, content area on the right, controls at the bottom of the rail.
- Modify `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/FloatingRootView.kt`: add resize handle in expanded mode and invoke a width resize callback.
- Modify `debugtools-core/src/main/kotlin/com/debugtools/core/window/FloatingWindowManager.kt`: keep expanded width mutable, clamp width, and expose resize callback to root view.
- Add tests under `debugtools-core/src/test/kotlin/com/debugtools/core/window/`.

## Task 1: Vertical Tab Rail and Theme

**Files:**
- Create: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/DebugToolsTheme.kt`
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/TabBarView.kt`
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/ExpandedView.kt`
- Test: `debugtools-core/src/test/kotlin/com/debugtools/core/window/ExpandedViewTest.kt`

**Interfaces:**
- Produces: `DebugToolsTheme`, `TabBarView` as a vertical `ScrollView`, `ExpandedView.tabRailWidthPxForTest()`.
- Consumes: existing `DebugModule.createContentView(context)`.

- [ ] **Step 1: Write failing layout test**

Create `ExpandedViewTest.kt` with tests that instantiate `ExpandedView`, set two fake modules, and assert that the first child is a horizontal root containing a fixed-width vertical tab rail and a content area.

- [ ] **Step 2: Run RED**

Run `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.window.ExpandedViewTest`.
Expected: fail because the test file or helper APIs do not exist yet.

- [ ] **Step 3: Implement theme and vertical layout**

Add `DebugToolsTheme`, convert `TabBarView` to a vertical rail, and make `ExpandedView` horizontal with tab rail left and content right.

- [ ] **Step 4: Run GREEN**

Run `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.window.ExpandedViewTest`.
Expected: pass.

## Task 2: Manual Expanded Panel Resize

**Files:**
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/FloatingWindowManager.kt`
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/FloatingRootView.kt`
- Test: `debugtools-core/src/test/kotlin/com/debugtools/core/window/FloatingWindowManagerTest.kt`

**Interfaces:**
- Produces: `FloatingWindowManager.resizeExpandedBy(deltaPx: Int)`, `FloatingWindowManager.expandedWidthForTest()`.
- Consumes: `WindowManager.updateViewLayout(rootView, layoutParams)` and existing expanded display mode.

- [ ] **Step 1: Write failing resize test**

Add tests that verify expanded width grows/shrinks, clamps to min/max, and does not resize minimized/brief dimensions.

- [ ] **Step 2: Run RED**

Run `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.window.FloatingWindowManagerTest`.
Expected: fail because resize APIs do not exist.

- [ ] **Step 3: Implement resize handle and width clamping**

Make expanded width mutable, clamp to 28%-70% screen width, pass a resize callback to `FloatingRootView`, and add a left-edge drag handle shown only in expanded mode.

- [ ] **Step 4: Run GREEN**

Run `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.window.FloatingWindowManagerTest`.
Expected: pass.

## Task 3: Verification

**Files:**
- No additional production files.

- [ ] **Step 1: Run targeted tests**

Run `./gradlew :debugtools-core:testDebugUnitTest`.
Expected: pass.

- [ ] **Step 2: Build sample app**

Run `./gradlew :app:assembleDebug`.
Expected: pass.

- [ ] **Step 3: Commit**

Commit changed files with message `feat(core): add car console layout`.
