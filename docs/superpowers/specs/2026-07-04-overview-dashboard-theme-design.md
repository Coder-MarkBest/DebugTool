# Overview Dashboard And Tool Theme Design

## Goal

Build a first-tab overview dashboard for DebugTools that uses existing module capabilities to show module-level health, then align the expanded window visual style around a dense car-console debug workflow.

## User Context

- The tool is used only by product, engineering, and QA staff during debug, not while driving.
- The overview should use capabilities already present in the project. It must not add new collection pipelines.
- The overview should prefer module entry and scanning over a sparse issue-only page.
- The UI should feel like a practical debug console: compact, stable, low-noise, and easy to scan on a large car-console display.

## Scope

### In Scope

- Add a core overview protocol that optional modules can implement.
- Add a fixed first tab named "总览".
- Render a dense module summary list, with modules sorted by severity.
- Let users tap a summary row to jump to the corresponding module tab.
- Connect first-version summaries for existing modules where data is already available.
- Normalize visual tokens and row/list styling used by the overview and shell.

### Out Of Scope

- New performance sampling, new audio analysis, new network capture data, or new stability collection.
- Cross-module root-cause analysis.
- Multi-turn requestId aggregation.
- Charts or large dashboard cards.
- Sharing/export flows.
- Changing the current resize bug state in this design pass.

## Overview Protocol

Create a small optional interface in `debugtools-core`:

```kotlin
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

enum class OverviewStatus {
    OK,
    WARNING,
    ERROR,
    RECORDING,
    UNKNOWN
}

data class OverviewMetric(
    val label: String,
    val value: String,
    val status: OverviewStatus = OverviewStatus.UNKNOWN
)
```

`DebugModule` should not require this interface. The overview tab only reads modules that also implement `OverviewProvider`.

## First-Version Module Summaries

Use existing state and persisted data only:

- Conversation: latest requestId, turn count, failure count, slow stage count.
- Startup: latest startup status, failed step count, slow step count, never-ended step count.
- Stability: crash count and monitored process status where already available.
- Perf monitor: current CPU and memory summary where already available.
- Audio monitor: current recording state, stream availability, anomaly count where already available.
- OkHttp capture: HTTP count, failure count, slow request count where already available.
- Network: network type, ping, quality where already available.
- General: disk and process-alive summary where already available.

If a module cannot produce a reliable summary from its current public/local state, skip the metric rather than inventing one.

## Interaction

- "总览" is always the first tab in expanded mode.
- Summary rows are ordered by severity: `ERROR`, `WARNING`, `RECORDING`, `UNKNOWN`, `OK`.
- Tapping a row jumps to that module tab.
- The overview refreshes when opened and when the expanded view is rebuilt.
- No filtering in the first version.

## Visual Direction

- Dense rows instead of large cards.
- Stable row height, clear dividers, compact labels, and no decorative backgrounds.
- Color semantics:
  - ERROR: red, for functional failure, crash, or unavailable critical path.
  - WARNING: yellow, for slow stages, unfinished spans, degraded network/audio/perf state.
  - RECORDING: green or active accent, for active recording state.
  - OK: muted green or neutral text, secondary priority.
  - UNKNOWN: gray, for unsupported or unavailable summary state.
- Keep the top recording bar fixed-height and visually stable.
- Keep the left tab rail compact. The overview should not make tab labels larger.

## Testing Requirements

- Unit-test overview sorting and provider extraction in core.
- Unit-test overview tab insertion and row-click tab jump.
- Unit-test at least conversation/startup summary generation because they are voice-assistant critical.
- Build and run the sample app on emulator.
- Verify visually that:
  - "总览" appears first.
  - summary rows render compactly.
  - row tap jumps to the target module.
  - existing tabs still open.
  - recording bar remains stable.

