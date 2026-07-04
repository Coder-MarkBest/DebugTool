# Overview Dashboard And Tool Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fixed first-tab overview dashboard that summarizes existing module health and align the expanded tool UI with a compact car-console debug style.

**Architecture:** Add an optional `OverviewProvider` protocol in `debugtools-core`, render a synthetic overview tab in `ExpandedView`, and let existing modules opt in by returning lightweight summaries from their current state. Keep module boundaries intact; the overview reads public provider output only.

**Tech Stack:** Android Kotlin, programmatic Views, existing DebugTools module registration, Robolectric unit tests, Gradle debug unit tests, emulator demo validation.

## Global Constraints

- Do not change the current resize bug state as part of this work.
- Do not add new collection pipelines or background samplers.
- Do not make `DebugModule` implementations mandatory overview providers.
- Do not introduce XML layouts or new UI frameworks.
- Keep the overview compact: dense rows, no chart dashboard, no large cards.
- Verify in the sample app on the connected emulator before calling implementation complete.

---

## File Structure

- Create `debugtools-core/src/main/kotlin/com/debugtools/core/overview/OverviewProvider.kt` for protocol data types.
- Create `debugtools-core/src/main/kotlin/com/debugtools/core/overview/OverviewAggregator.kt` for provider extraction and sorting.
- Create `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/OverviewView.kt` for the overview list UI.
- Modify `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/ExpandedView.kt` to insert the overview tab and handle row-to-tab navigation.
- Modify `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/TabBarView.kt` only if its tab selection API needs external selection support.
- Modify `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/DebugToolsTheme.kt` for shared status colors and row styling tokens.
- Modify selected modules to implement `OverviewProvider` using existing data:
  - `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/ConversationMonitorModule.kt`
  - `debugtools-startup/src/main/kotlin/com/debugtools/startup/StartupMonitorModule.kt`
  - `debugtools-stability/src/main/kotlin/com/debugtools/stability/StabilityModule.kt`
  - `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/PerfMonitorModule.kt`
  - `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/AudioMonitorModule.kt`
  - `debugtools-okhttp-capture/src/main/kotlin/com/debugtools/okhttp/NetworkCaptureModule.kt`
  - `debugtools-network/src/main/kotlin/com/debugtools/network/NetworkModule.kt`
  - `debugtools-general/src/main/kotlin/com/debugtools/general/GeneralModule.kt`
- Add tests:
  - `debugtools-core/src/test/kotlin/com/debugtools/core/overview/OverviewAggregatorTest.kt`
  - `debugtools-core/src/test/kotlin/com/debugtools/core/window/OverviewViewTest.kt`
  - Extend `debugtools-core/src/test/kotlin/com/debugtools/core/window/ExpandedViewTest.kt`
  - Add or extend module tests for conversation and startup overview summaries.

---

### Task 1: Core Overview Protocol And Sorting

**Files:**
- Create: `debugtools-core/src/main/kotlin/com/debugtools/core/overview/OverviewProvider.kt`
- Create: `debugtools-core/src/main/kotlin/com/debugtools/core/overview/OverviewAggregator.kt`
- Test: `debugtools-core/src/test/kotlin/com/debugtools/core/overview/OverviewAggregatorTest.kt`

**Interfaces:**
- Consumes: `DebugModule`
- Produces:
  - `interface OverviewProvider`
  - `data class OverviewItem`
  - `enum class OverviewStatus`
  - `data class OverviewMetric`
  - `object OverviewAggregator { fun collect(modules: List<DebugModule>): List<OverviewItem> }`

- [ ] **Step 1: Write failing protocol extraction and sorting tests**

```kotlin
@Test fun `collect returns items from overview providers only sorted by severity`() {
    val modules = listOf(
        FakeModule("plain", "普通"),
        ProviderModule(OverviewItem("ok", "正常", OverviewStatus.OK, "正常")),
        ProviderModule(OverviewItem("err", "错误", OverviewStatus.ERROR, "失败")),
        ProviderModule(OverviewItem("warn", "警告", OverviewStatus.WARNING, "慢"))
    )

    val items = OverviewAggregator.collect(modules)

    assertEquals(listOf("err", "warn", "ok"), items.map { it.moduleId })
}
```

- [ ] **Step 2: Run the failing test**

Run: `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.overview.OverviewAggregatorTest`

Expected: compile failure because overview types do not exist.

- [ ] **Step 3: Add protocol and aggregator**

```kotlin
package com.debugtools.core.overview

interface OverviewProvider {
    fun getOverviewItems(): List<OverviewItem>
}

data class OverviewItem(
    val moduleId: String,
    val title: String,
    val status: OverviewStatus,
    val primaryText: String,
    val secondaryText: String? = null,
    val metrics: List<OverviewMetric> = emptyList()
)

enum class OverviewStatus { OK, WARNING, ERROR, RECORDING, UNKNOWN }

data class OverviewMetric(
    val label: String,
    val value: String,
    val status: OverviewStatus = OverviewStatus.UNKNOWN
)
```

```kotlin
package com.debugtools.core.overview

import com.debugtools.core.module.DebugModule

object OverviewAggregator {
    fun collect(modules: List<DebugModule>): List<OverviewItem> =
        modules
            .flatMap { (it as? OverviewProvider)?.getOverviewItems().orEmpty() }
            .sortedWith(compareBy<OverviewItem> { rank(it.status) }.thenBy { it.title })

    private fun rank(status: OverviewStatus): Int = when (status) {
        OverviewStatus.ERROR -> 0
        OverviewStatus.WARNING -> 1
        OverviewStatus.RECORDING -> 2
        OverviewStatus.UNKNOWN -> 3
        OverviewStatus.OK -> 4
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.overview.OverviewAggregatorTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add debugtools-core/src/main/kotlin/com/debugtools/core/overview debugtools-core/src/test/kotlin/com/debugtools/core/overview
git commit -m "feat: add overview protocol"
```

---

### Task 2: Overview View And First Tab Wiring

**Files:**
- Create: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/OverviewView.kt`
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/ExpandedView.kt`
- Modify if needed: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/TabBarView.kt`
- Test: `debugtools-core/src/test/kotlin/com/debugtools/core/window/OverviewViewTest.kt`
- Test: `debugtools-core/src/test/kotlin/com/debugtools/core/window/ExpandedViewTest.kt`

**Interfaces:**
- Consumes: `OverviewAggregator.collect(modules)`, `OverviewItem`
- Produces:
  - `class OverviewView(context: Context) : ScrollView`
  - `fun OverviewView.update(items: List<OverviewItem>, onModuleClick: (String) -> Unit)`
  - Expanded view behavior: synthetic "总览" tab at index `0`

- [ ] **Step 1: Write failing tests for first tab and row click**

```kotlin
@Test fun `expanded view inserts overview as first tab`() {
    val view = ExpandedView(context)
    view.setModules(listOf(FakeModule("conversation", "对话链路")))

    assertEquals("总览", view.tabTitleForTest(0))
    assertEquals("对话链路", view.tabTitleForTest(1))
}

@Test fun `overview row click opens matching module tab`() {
    val module = ProviderFakeModule("conversation", "对话链路")
    val view = ExpandedView(context)
    view.setModules(listOf(module))

    view.clickOverviewRowForTest("conversation")

    assertEquals(1, view.selectedTabIndexForTest())
}
```

- [ ] **Step 2: Run the failing tests**

Run: `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.window.ExpandedViewTest`

Expected: compile failure for missing test APIs and overview view.

- [ ] **Step 3: Implement `OverviewView` with dense rows**

```kotlin
class OverviewView(context: Context) : ScrollView(context) {
    private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(DebugToolsTheme.background)
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun update(items: List<OverviewItem>, onModuleClick: (String) -> Unit) {
        container.removeAllViews()
        items.forEach { item ->
            container.addView(buildRow(item, onModuleClick))
        }
    }

    private fun buildRow(item: OverviewItem, onModuleClick: (String) -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(DebugToolsTheme.panel)
            isClickable = true
            setOnClickListener { onModuleClick(item.moduleId) }
            addView(TextView(context).apply {
                text = item.title
                setTextColor(DebugToolsTheme.primaryText)
                textSize = 14f
            })
            addView(TextView(context).apply {
                text = item.primaryText
                setTextColor(DebugToolsTheme.colorFor(item.status))
                textSize = 13f
            })
            item.secondaryText?.let { secondary ->
                addView(TextView(context).apply {
                    text = secondary
                    setTextColor(DebugToolsTheme.secondaryText)
                    textSize = 12f
                })
            }
        }
    }

    private fun dp(value: Int): Int = DebugToolsTheme.dp(resources, value)
}
```

- [ ] **Step 4: Wire synthetic overview tab in `ExpandedView`**

Implementation details:
- Keep `modules: List<DebugModule>` in `ExpandedView`.
- Build tabs from `listOf(overviewSyntheticModule) + modules`, or extend `TabBarView.setTabs` to accept title/id pairs.
- When selected index is `0`, show `OverviewView`.
- When a row is clicked, find `modules.indexOfFirst { it.id == moduleId }`, then show tab index `moduleIndex + 1`.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.window.ExpandedViewTest --tests com.debugtools.core.window.OverviewViewTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add debugtools-core/src/main/kotlin/com/debugtools/core/window/view debugtools-core/src/test/kotlin/com/debugtools/core/window
git commit -m "feat: add overview tab"
```

---

### Task 3: Conversation And Startup Overview Providers

**Files:**
- Modify: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/ConversationMonitorModule.kt`
- Modify: `debugtools-startup/src/main/kotlin/com/debugtools/startup/StartupMonitorModule.kt`
- Test: existing conversation and startup module test folders

**Interfaces:**
- Consumes: `OverviewProvider`
- Produces:
  - Conversation overview item with latest requestId, turn count, failure count, slow stage count.
  - Startup overview item with latest status, failed step count, slow step count, never-ended step count.

- [ ] **Step 1: Write failing conversation overview test**

```kotlin
@Test fun `conversation overview summarizes current session`() {
    val module = ConversationMonitorModule()

    val item = module.getOverviewItems().single()

    assertEquals("conversation", item.moduleId)
    assertEquals("对话链路", item.title)
}
```

Use existing tracer test helpers or seed persisted sessions in the same way current conversation tests seed data. If no session exists, expected item should be `UNKNOWN` with primary text `"暂无对话数据"`.

- [ ] **Step 2: Write failing startup overview test**

```kotlin
@Test fun `startup overview reports failed and slow steps`() {
    val module = StartupMonitorModule()

    val item = module.getOverviewItems().single()

    assertEquals("startup", item.moduleId)
    assertEquals("启动链路", item.title)
}
```

Use existing startup session fixtures. If no startup session exists, expected item should be `UNKNOWN` with primary text `"暂无启动数据"`.

- [ ] **Step 3: Run failing module tests**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest :debugtools-startup:testDebugUnitTest`

Expected: compile failure or assertion failure before provider implementation.

- [ ] **Step 4: Implement providers using existing analyzers**

Conversation rules:
- `ERROR` if latest turn/session has failed outcome.
- `WARNING` if slow stage count is greater than zero.
- `OK` if latest session exists and has no failures or slow stages.
- `UNKNOWN` if no session exists.

Startup rules:
- `ERROR` if failed step count is greater than zero.
- `WARNING` if slow step count or never-ended count is greater than zero.
- `OK` if latest session exists and analyzer returns no issues.
- `UNKNOWN` if no session exists.

- [ ] **Step 5: Run module tests**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest :debugtools-startup:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add debugtools-conversation debugtools-startup
git commit -m "feat: summarize voice-critical modules"
```

---

### Task 4: Remaining Module Overview Providers

**Files:**
- Modify: `debugtools-stability/src/main/kotlin/com/debugtools/stability/StabilityModule.kt`
- Modify: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/PerfMonitorModule.kt`
- Modify: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/AudioMonitorModule.kt`
- Modify: `debugtools-okhttp-capture/src/main/kotlin/com/debugtools/okhttp/NetworkCaptureModule.kt`
- Modify: `debugtools-network/src/main/kotlin/com/debugtools/network/NetworkModule.kt`
- Modify: `debugtools-general/src/main/kotlin/com/debugtools/general/GeneralModule.kt`

**Interfaces:**
- Consumes: `OverviewProvider`
- Produces: One overview item per module where reliable current state already exists.

- [ ] **Step 1: Inspect each module for existing state**

Read the module/presenter/repository files and write down exactly which values are already available. Skip any metric that requires adding a collector.

- [ ] **Step 2: Add focused tests for modules with stable state APIs**

For modules that already have testable repositories or state objects, assert the generated `OverviewItem.status`, `primaryText`, and metric count.

- [ ] **Step 3: Implement providers conservatively**

Rules:
- If a module has no reliable current state, return an `UNKNOWN` item with an honest primary text.
- If a module can expose a meaningful count from existing storage, use that count.
- Do not read private files from core or overview UI.

- [ ] **Step 4: Run module tests**

Run: `./gradlew testDebugUnitTest`

Expected: PASS for all debug unit tests.

- [ ] **Step 5: Commit**

```bash
git add debugtools-stability debugtools-perfmon debugtools-audiomon debugtools-okhttp-capture debugtools-network debugtools-general
git commit -m "feat: add module overview summaries"
```

---

### Task 5: Visual Theme Pass

**Files:**
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/DebugToolsTheme.kt`
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/RecordingBarView.kt`
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/TabBarView.kt`
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/ExpandedView.kt`
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/OverviewView.kt`
- Test: existing core window tests

**Interfaces:**
- Consumes: existing view classes.
- Produces: shared status colors and compact, stable shell styling.

- [ ] **Step 1: Add theme helper tests where practical**

```kotlin
@Test fun `theme maps warning status to warning color`() {
    assertEquals(DebugToolsTheme.warning, DebugToolsTheme.colorFor(OverviewStatus.WARNING))
}
```

- [ ] **Step 2: Add status color helper**

```kotlin
fun colorFor(status: OverviewStatus): Int = when (status) {
    OverviewStatus.OK -> success
    OverviewStatus.WARNING -> warning
    OverviewStatus.ERROR -> danger
    OverviewStatus.RECORDING -> success
    OverviewStatus.UNKNOWN -> secondaryText
}
```

- [ ] **Step 3: Normalize compact shell dimensions**

Use shared values in `DebugToolsTheme`:
- `rowMinHeightDp = 56`
- `rowPaddingHorizontalDp = 14`
- `rowPaddingVerticalDp = 10`
- `recordingBarHeightDp = 48`
- `tabRailWidthDp = 72`

- [ ] **Step 4: Run core tests**

Run: `./gradlew :debugtools-core:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add debugtools-core/src/main/kotlin/com/debugtools/core/window/view debugtools-core/src/test/kotlin/com/debugtools/core
git commit -m "style: align debug console theme"
```

---

### Task 6: Sample App Runtime Verification

**Files:**
- Modify only if necessary: `app/src/main/kotlin/com/debugtools/sample/MainActivity.kt`
- No production code changes expected unless demo data must be triggered by existing buttons.

**Interfaces:**
- Consumes: registered sample modules.
- Produces: emulator proof that overview works.

- [ ] **Step 1: Build and install sample app**

Run: `./gradlew :app:installDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Start app and grant permissions**

Run:

```bash
adb shell appops set com.debugtools.sample SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.debugtools.sample android.permission.RECORD_AUDIO || true
adb shell am force-stop com.debugtools.sample
adb shell monkey -p com.debugtools.sample 1
```

Expected: sample app opens.

- [ ] **Step 3: Initialize DebugTools and capture screenshot**

Use `adb shell input tap` on the "② 初始化 DebugTools" button coordinates from the current emulator. Then capture:

```bash
adb exec-out screencap -p > /tmp/debugtools_overview_initial.png
```

Expected: overlay opens with "总览" as first selected tab.

- [ ] **Step 4: Generate demo data**

Tap existing sample buttons:
- "生成示例对话链路（3 个 requestId）"
- "生成示例启动会话（5 条）"
- "发送 1 个 mock HTTP 请求"

Expected: overview rows update after reopening or switching back to "总览".

- [ ] **Step 5: Verify row navigation**

Tap the "对话链路" overview row.

Expected: selected tab changes to "对话链路" and the conversation module content appears.

- [ ] **Step 6: Capture final screenshot**

```bash
adb exec-out screencap -p > /tmp/debugtools_overview_final.png
```

Expected: compact rows, clear status colors, no large cards, recording bar stable.

- [ ] **Step 7: Run full relevant tests**

Run:

```bash
./gradlew :debugtools-core:testDebugUnitTest \
  :debugtools-conversation:testDebugUnitTest \
  :debugtools-startup:testDebugUnitTest \
  :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit verification support changes if any**

If no app code changed, skip this commit. If demo wiring changed:

```bash
git add app/src/main/kotlin/com/debugtools/sample/MainActivity.kt
git commit -m "test: verify overview demo flow"
```

---

## Self-Review

- Spec coverage: protocol, first tab, module summaries, visual pass, and emulator verification are covered by Tasks 1-6.
- Placeholder scan: no `TBD`, `TODO`, or unspecified implementation steps remain.
- Type consistency: `OverviewProvider`, `OverviewItem`, `OverviewMetric`, `OverviewStatus`, and `OverviewAggregator.collect` are defined in Task 1 and reused consistently later.
- Scope check: this is one coherent feature because the visual pass is scoped to the overview shell and does not introduce new module capabilities.

