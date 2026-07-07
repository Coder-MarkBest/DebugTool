# Task 3 Report: Flow Builder and Coroutine Scheduler

## Scope

Implemented the public startup init flow builder and coroutine scheduler in `startup-init-flow` only:

- `StartupInitFlow.builder()`
- `StartupInitFlow.Builder`
- `InitFlowRunner`
- `InitFlowRunner.run(): InitFlowResult`
- focused runner tests for ordering, dependency failure propagation, callback completion, and invalid graph handling

## What Changed

- Added `StartupInitFlow` as the entry point for assembling init tasks.
- Added `InitFlowRunner` to validate the graph, schedule ready tasks, skip downstream tasks blocked by failed dependencies, and return `InitFlowResult`.
- Kept the core module independent from any `com.debugtools.*` startup types.
- Preserved support for synchronous, suspend, and callback-style task initialization via the existing `InitTaskBuilder`.

## Verification

Executed:

```bash
./gradlew :startup-init-flow:test --tests com.debugtools.startupinit.InitFlowRunnerTest
./gradlew :startup-init-flow:test
```

Both commands completed successfully.

## Notes

- No timeout, retry, AndroidX Startup integration, cross-process scheduling, or network reporting was added.
- No concerns remain from the implemented Task 3 scope.

## Review Fix Evidence

### RED

Added a focused regression test for the batch-gating bug:

- `dependent task starts as soon as dependency succeeds without waiting for unrelated task`

Command:

```bash
./gradlew :startup-init-flow:test --tests com.debugtools.startupinit.InitFlowRunnerTest.dependent\ task\ starts\ as\ soon\ as\ dependency\ succeeds\ without\ waiting\ for\ unrelated\ task
```

Observed failure before the runner change:

```text
com.debugtools.startupinit.InitFlowRunnerTest > dependent task starts as soon as dependency succeeds without waiting for unrelated task FAILED
    kotlinx.coroutines.TimeoutCancellationException
```

This reproduced the reported scheduler issue: `config` completed, `net` remained suspended, and dependent `asr` never started until the whole ready batch finished.

### GREEN

Updated `InitFlowRunner` to consume task completions one by one and launch newly ready tasks immediately after each completion.

Commands:

```bash
./gradlew :startup-init-flow:test --tests com.debugtools.startupinit.InitFlowRunnerTest
./gradlew :startup-init-flow:test
```

Observed result after the runner change:

```text
BUILD SUCCESSFUL
```

The new regression test now proves `asr` starts before `net` is released, which catches the original batch-gating bug and keeps the intended dependency scheduling behavior covered.
