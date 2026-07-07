# Startup Init Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a DebugTools-independent application initialization flow library, plus an optional DebugTools bridge that records the flow into the existing startup timeline.

**Architecture:** Add a pure Kotlin/JVM `:startup-init-flow` module for task definitions, dependency graph validation, scheduling, async adapters, and reporter events. Add a separate Android library `:debugtools-startup-init` that depends on `:startup-init-flow` and `:debugtools-startup` only to bridge reporter callbacks into `AppStartupMonitor`. Update the sample app and integration docs to use the bridge without making the core flow depend on DebugTools.

**Tech Stack:** Kotlin 1.9.22, JVM target 17, Kotlin coroutines 1.7.3, JUnit 4, Android Gradle Plugin 8.5.2 for the optional bridge.

## Global Constraints

- `startup-init-flow` must not import or depend on any `com.debugtools.*` type.
- `startup-init-flow` depends only on Kotlin stdlib and Kotlin coroutines.
- `debugtools-startup-init` is the only new module allowed to depend on `debugtools-startup`.
- The core flow must work without DebugTools and return `InitFlowResult`.
- `reportToStartupMonitor()` must be an extension supplied by `debugtools-startup-init`, not a core API.
- Synchronous, suspend, and callback initialization styles must be supported.
- Dependency failures skip downstream tasks while independent branches continue.
- First version does not implement timeout, retry, AndroidX Startup integration, cross-process scheduling, or network reporting.
- TDD is required: every behavior change starts with a failing test.

---

## File Structure

Create:

- `startup-init-flow/build.gradle.kts`: pure Kotlin/JVM module.
- `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/StartupInitFlow.kt`: public builder entrypoint.
- `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitTask.kt`: task model, task builder, task runner type.
- `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitReporter.kt`: reporter interface and no-op reporter.
- `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitFlowResult.kt`: result/status models.
- `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitGraph.kt`: validation, missing dependency, cycle detection, ready-task helpers.
- `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitFlowRunner.kt`: coroutine scheduler and execution loop.
- `startup-init-flow/src/test/kotlin/com/debugtools/startupinit/InitGraphTest.kt`
- `startup-init-flow/src/test/kotlin/com/debugtools/startupinit/InitFlowRunnerTest.kt`
- `startup-init-flow/src/test/kotlin/com/debugtools/startupinit/NoDebugToolsUsageTest.kt`
- `debugtools-startup-init/build.gradle.kts`: optional Android bridge.
- `debugtools-startup-init/src/main/AndroidManifest.xml`
- `debugtools-startup-init/src/main/kotlin/com/debugtools/startupinit/debugtools/StartupMonitorReporter.kt`
- `debugtools-startup-init/src/main/kotlin/com/debugtools/startupinit/debugtools/StartupInitFlowDebugToolsExt.kt`
- `debugtools-startup-init/src/test/kotlin/com/debugtools/startupinit/debugtools/StartupMonitorReporterTest.kt`

Modify:

- `build.gradle.kts`: add `org.jetbrains.kotlin.jvm` plugin version.
- `settings.gradle.kts`: include `:startup-init-flow` and `:debugtools-startup-init`.
- `app/build.gradle.kts`: depend on `:debugtools-startup-init`.
- `app/src/main/kotlin/com/debugtools/sample/SampleApplication.kt`: replace hand-written startup monitoring with `StartupInitFlow`.
- `docs/INTEGRATION.md`: document standalone and DebugTools-bridged usage.

---

### Task 1: Pure Kotlin Module Scaffolding and Core Models

**Files:**
- Modify: `build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `startup-init-flow/build.gradle.kts`
- Create: `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitFlowResult.kt`
- Create: `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitReporter.kt`
- Create: `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitTask.kt`
- Create: `startup-init-flow/src/test/kotlin/com/debugtools/startupinit/NoDebugToolsUsageTest.kt`

**Interfaces:**
- Produces:
  - `enum class InitTaskStatus { SUCCESS, FAILED, SKIPPED }`
  - `data class InitTaskResult(val name: String, val status: InitTaskStatus, val error: String? = null)`
  - `data class InitFlowResult(val taskResults: List<InitTaskResult>, val success: Boolean)`
  - `interface InitReporter`
  - `object NoOpInitReporter : InitReporter`
  - `internal data class InitTask`
  - `class InitTaskBuilder`
- Consumes: none.

- [ ] **Step 1: Write the failing standalone usage test**

Create `startup-init-flow/src/test/kotlin/com/debugtools/startupinit/NoDebugToolsUsageTest.kt`:

```kotlin
package com.debugtools.startupinit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoDebugToolsUsageTest {
    @Test
    fun `core package exposes no debugtools dependency names`() {
        val apiClassNames = listOf(
            InitFlowResult::class.java.name,
            InitTaskResult::class.java.name,
            InitTaskStatus::class.java.name,
            InitReporter::class.java.name,
            NoOpInitReporter::class.java.name
        )

        assertTrue(apiClassNames.all { it.startsWith("com.debugtools.startupinit") })
        assertFalse(apiClassNames.any { it.contains(".startup.") || it.contains(".core.") })
    }

    @Test
    fun `result success is true only when all tasks succeed`() {
        val ok = InitFlowResult(
            taskResults = listOf(InitTaskResult("config", InitTaskStatus.SUCCESS)),
            success = true
        )
        val failed = InitFlowResult(
            taskResults = listOf(InitTaskResult("asr", InitTaskStatus.FAILED, "boom")),
            success = false
        )

        assertEquals("config", ok.taskResults.single().name)
        assertTrue(ok.success)
        assertFalse(failed.success)
        assertEquals("boom", failed.taskResults.single().error)
    }
}
```

- [ ] **Step 2: Run the test to verify RED**

Run:

```bash
./gradlew :startup-init-flow:test --tests com.debugtools.startupinit.NoDebugToolsUsageTest
```

Expected: FAIL because `:startup-init-flow` is not included or `InitFlowResult` is unresolved.

- [ ] **Step 3: Add the Kotlin JVM plugin and module includes**

Modify root `build.gradle.kts` plugins block to include:

```kotlin
plugins {
    id("com.android.library") version "8.5.2" apply false
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "1.9.22" apply false
}
```

Modify `settings.gradle.kts` after `rootProject.name = "DebugTools"`:

```kotlin
include(":startup-init-flow")
include(":debugtools-startup-init")
include(":debugtools-core")
include(":debugtools-network")
include(":debugtools-timeline")
include(":debugtools-general")
include(":debugtools-okhttp-capture")
include(":debugtools-perfmon")
include(":debugtools-audiomon")
include(":debugtools-startup")
include(":debugtools-conversation")
include(":debugtools-stability")
include(":app")
```

Create `startup-init-flow/build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
```

- [ ] **Step 4: Add core result and reporter models**

Create `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitFlowResult.kt`:

```kotlin
package com.debugtools.startupinit

data class InitFlowResult(
    val taskResults: List<InitTaskResult>,
    val success: Boolean
)

data class InitTaskResult(
    val name: String,
    val status: InitTaskStatus,
    val error: String? = null
)

enum class InitTaskStatus {
    SUCCESS,
    FAILED,
    SKIPPED
}
```

Create `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitReporter.kt`:

```kotlin
package com.debugtools.startupinit

interface InitReporter {
    fun flowStarted(taskCount: Int) = Unit
    fun taskStarted(name: String, dependsOn: List<String>) = Unit
    fun taskSucceeded(name: String) = Unit
    fun taskFailed(name: String, error: Throwable) = Unit
    fun taskSkipped(name: String, failedDependencies: List<String>) = Unit
    fun flowFailed(reason: String) = Unit
    fun flowCompleted() = Unit
}

object NoOpInitReporter : InitReporter
```

Create `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitTask.kt`:

```kotlin
package com.debugtools.startupinit

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

internal data class InitTask(
    val name: String,
    val dependsOn: List<String>,
    val runner: suspend () -> Unit
)

class InitTaskBuilder {
    private var runner: (suspend () -> Unit)? = null

    fun runBlockingInit(block: () -> Unit) {
        runner = { block() }
    }

    fun suspendInit(block: suspend () -> Unit) {
        runner = block
    }

    fun callbackInit(block: (done: (Throwable?) -> Unit) -> Unit) {
        runner = {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                block { error ->
                    if (!resumed.compareAndSet(false, true)) return@block
                    if (error == null) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(error)
                    }
                }
            }
        }
    }

    internal fun buildRunner(): suspend () -> Unit =
        requireNotNull(runner) { "Init task must define runBlockingInit, suspendInit, or callbackInit" }
}
```

- [ ] **Step 5: Run the test to verify GREEN**

Run:

```bash
./gradlew :startup-init-flow:test --tests com.debugtools.startupinit.NoDebugToolsUsageTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts settings.gradle.kts startup-init-flow
git commit -m "feat: add standalone startup init flow core"
```

---

### Task 2: Dependency Graph Validation

**Files:**
- Create: `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitGraph.kt`
- Create: `startup-init-flow/src/test/kotlin/com/debugtools/startupinit/InitGraphTest.kt`

**Interfaces:**
- Consumes:
  - `internal data class InitTask(val name: String, val dependsOn: List<String>, val runner: suspend () -> Unit)`
- Produces:
  - `internal class InitGraph(private val tasks: List<InitTask>)`
  - `fun validate(): GraphValidationResult`
  - `fun readyTasks(completed: Set<String>, failedOrSkipped: Set<String>, started: Set<String>): List<InitTask>`
  - `fun blockedByFailures(failedOrSkipped: Set<String>, completed: Set<String>, alreadyResolved: Set<String>): Map<InitTask, List<String>>`
  - `data class GraphValidationResult(val valid: Boolean, val error: String? = null)`

- [ ] **Step 1: Write failing graph tests**

Create `startup-init-flow/src/test/kotlin/com/debugtools/startupinit/InitGraphTest.kt`:

```kotlin
package com.debugtools.startupinit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitGraphTest {
    @Test
    fun `validate rejects duplicate task names`() {
        val graph = InitGraph(listOf(task("config"), task("config")))

        val result = graph.validate()

        assertFalse(result.valid)
        assertEquals("Duplicate init task name: config", result.error)
    }

    @Test
    fun `validate rejects missing dependency`() {
        val graph = InitGraph(listOf(task("asr", dependsOn = listOf("config"))))

        val result = graph.validate()

        assertFalse(result.valid)
        assertEquals("Task asr depends on missing task config", result.error)
    }

    @Test
    fun `validate rejects dependency cycle`() {
        val graph = InitGraph(
            listOf(
                task("config", dependsOn = listOf("asr")),
                task("asr", dependsOn = listOf("config"))
            )
        )

        val result = graph.validate()

        assertFalse(result.valid)
        assertTrue(result.error!!.contains("Dependency cycle"))
    }

    @Test
    fun `ready tasks include only tasks whose dependencies completed`() {
        val config = task("config")
        val asr = task("asr", dependsOn = listOf("config"))
        val tts = task("tts", dependsOn = listOf("asr"))
        val net = task("net")
        val graph = InitGraph(listOf(config, asr, tts, net))

        assertEquals(listOf("config", "net"), graph.readyTasks(emptySet(), emptySet(), emptySet()).map { it.name })
        assertEquals(listOf("asr"), graph.readyTasks(setOf("config", "net"), emptySet(), setOf("config", "net")).map { it.name })
    }

    @Test
    fun `blocked by failures returns downstream tasks with failed dependencies`() {
        val graph = InitGraph(
            listOf(
                task("config"),
                task("asr", dependsOn = listOf("config")),
                task("tts", dependsOn = listOf("asr"))
            )
        )

        val blocked = graph.blockedByFailures(
            failedOrSkipped = setOf("config"),
            completed = emptySet(),
            alreadyResolved = setOf("config")
        )

        assertEquals(listOf("asr"), blocked.keys.map { it.name })
        assertEquals(listOf("config"), blocked.values.single())
    }

    private fun task(name: String, dependsOn: List<String> = emptyList()) =
        InitTask(name, dependsOn) {}
}
```

- [ ] **Step 2: Run graph tests to verify RED**

Run:

```bash
./gradlew :startup-init-flow:test --tests com.debugtools.startupinit.InitGraphTest
```

Expected: FAIL with unresolved reference `InitGraph`.

- [ ] **Step 3: Implement graph validation**

Create `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitGraph.kt`:

```kotlin
package com.debugtools.startupinit

internal data class GraphValidationResult(
    val valid: Boolean,
    val error: String? = null
)

internal class InitGraph(private val tasks: List<InitTask>) {
    private val byName = tasks.associateBy { it.name }

    fun validate(): GraphValidationResult {
        val duplicate = tasks.groupingBy { it.name }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) return GraphValidationResult(false, "Duplicate init task name: ${duplicate.key}")

        for (task in tasks) {
            for (dependency in task.dependsOn) {
                if (dependency !in byName) {
                    return GraphValidationResult(false, "Task ${task.name} depends on missing task $dependency")
                }
            }
        }

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(name: String, path: List<String>): String? {
            if (name in visiting) return (path + name).joinToString(" -> ")
            if (name in visited) return null
            visiting += name
            val task = byName.getValue(name)
            for (dependency in task.dependsOn) {
                val cycle = visit(dependency, path + name)
                if (cycle != null) return cycle
            }
            visiting -= name
            visited += name
            return null
        }

        for (task in tasks) {
            val cycle = visit(task.name, emptyList())
            if (cycle != null) return GraphValidationResult(false, "Dependency cycle: $cycle")
        }
        return GraphValidationResult(true)
    }

    fun readyTasks(
        completed: Set<String>,
        failedOrSkipped: Set<String>,
        started: Set<String>
    ): List<InitTask> =
        tasks.filter { task ->
            task.name !in started &&
                task.name !in completed &&
                task.name !in failedOrSkipped &&
                task.dependsOn.all { it in completed }
        }

    fun blockedByFailures(
        failedOrSkipped: Set<String>,
        completed: Set<String>,
        alreadyResolved: Set<String>
    ): Map<InitTask, List<String>> =
        tasks
            .filter { task ->
                task.name !in alreadyResolved &&
                    task.name !in completed &&
                    task.name !in failedOrSkipped
            }
            .mapNotNull { task ->
                val failedDependencies = task.dependsOn.filter { it in failedOrSkipped }
                if (failedDependencies.isEmpty()) null else task to failedDependencies
            }
            .toMap()
}
```

- [ ] **Step 4: Run graph tests to verify GREEN**

Run:

```bash
./gradlew :startup-init-flow:test --tests com.debugtools.startupinit.InitGraphTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitGraph.kt startup-init-flow/src/test/kotlin/com/debugtools/startupinit/InitGraphTest.kt
git commit -m "feat: validate startup init dependency graph"
```

---

### Task 3: Flow Builder and Coroutine Scheduler

**Files:**
- Create: `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/StartupInitFlow.kt`
- Create: `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitFlowRunner.kt`
- Create: `startup-init-flow/src/test/kotlin/com/debugtools/startupinit/InitFlowRunnerTest.kt`

**Interfaces:**
- Consumes:
  - `InitTaskBuilder.buildRunner(): suspend () -> Unit`
  - `InitGraph.validate()`
  - `InitGraph.readyTasks(completed: Set<String>, failedOrSkipped: Set<String>, started: Set<String>)`
  - `InitGraph.blockedByFailures(failedOrSkipped: Set<String>, completed: Set<String>, alreadyResolved: Set<String>)`
- Produces:
  - `object StartupInitFlow { fun builder(): Builder }`
  - `class StartupInitFlow.Builder`
  - `class InitFlowRunner`
  - `suspend fun InitFlowRunner.run(): InitFlowResult`

- [ ] **Step 1: Write failing scheduler tests**

Create `startup-init-flow/src/test/kotlin/com/debugtools/startupinit/InitFlowRunnerTest.kt`:

```kotlin
package com.debugtools.startupinit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitFlowRunnerTest {
    @Test
    fun `independent tasks run before dependent tasks`() = runTest {
        val events = mutableListOf<String>()

        val result = StartupInitFlow.builder()
            .task("config") { suspendInit { events += "config" } }
            .task("net") { suspendInit { events += "net" } }
            .task("asr", dependsOn = listOf("config")) { suspendInit { events += "asr" } }
            .run()

        assertTrue(result.success)
        assertTrue(events.indexOf("asr") > events.indexOf("config"))
        assertEquals(setOf("config", "net", "asr"), events.toSet())
    }

    @Test
    fun `failed dependency skips downstream and independent branch continues`() = runTest {
        val events = mutableListOf<String>()

        val result = StartupInitFlow.builder()
            .task("config") { suspendInit { error("missing config") } }
            .task("asr", dependsOn = listOf("config")) { suspendInit { events += "asr" } }
            .task("net") { suspendInit { events += "net" } }
            .run()

        assertFalse(result.success)
        assertEquals(listOf("net"), events)
        assertEquals(InitTaskStatus.FAILED, result.taskResults.first { it.name == "config" }.status)
        assertEquals(InitTaskStatus.SKIPPED, result.taskResults.first { it.name == "asr" }.status)
        assertEquals(InitTaskStatus.SUCCESS, result.taskResults.first { it.name == "net" }.status)
    }

    @Test
    fun `callback task waits for done and ignores repeated done`() = runTest {
        val doneRef = CompletableDeferred<(Throwable?) -> Unit>()
        val flow = async {
            StartupInitFlow.builder()
                .task("callback") {
                    callbackInit { done -> doneRef.complete(done) }
                }
                .run()
        }

        val done = doneRef.await()
        assertFalse(flow.isCompleted)
        done(null)
        done(IllegalStateException("late failure"))

        val result = flow.await()
        assertTrue(result.success)
        assertEquals(InitTaskStatus.SUCCESS, result.taskResults.single().status)
    }

    @Test
    fun `invalid graph returns failed result without running tasks`() = runTest {
        var ran = false

        val result = StartupInitFlow.builder()
            .task("asr", dependsOn = listOf("missing")) { suspendInit { ran = true } }
            .run()

        assertFalse(ran)
        assertFalse(result.success)
        assertEquals("init_flow_invalid_graph", result.taskResults.single().name)
        assertEquals(InitTaskStatus.FAILED, result.taskResults.single().status)
    }
}
```

- [ ] **Step 2: Run scheduler tests to verify RED**

Run:

```bash
./gradlew :startup-init-flow:test --tests com.debugtools.startupinit.InitFlowRunnerTest
```

Expected: FAIL with unresolved reference `StartupInitFlow`.

- [ ] **Step 3: Implement StartupInitFlow builder**

Create `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/StartupInitFlow.kt`:

```kotlin
package com.debugtools.startupinit

object StartupInitFlow {
    fun builder(): Builder = Builder()

    class Builder {
        private val tasks = mutableListOf<InitTask>()
        private var reporter: InitReporter = NoOpInitReporter

        fun task(
            name: String,
            dependsOn: List<String> = emptyList(),
            block: InitTaskBuilder.() -> Unit
        ): Builder = apply {
            require(name.isNotBlank()) { "Init task name must not be blank" }
            val builder = InitTaskBuilder().apply(block)
            tasks += InitTask(name = name, dependsOn = dependsOn, runner = builder.buildRunner())
        }

        fun reporter(reporter: InitReporter): Builder = apply {
            this.reporter = reporter
        }

        fun build(): InitFlowRunner =
            InitFlowRunner(tasks = tasks.toList(), reporter = reporter)

        suspend fun run(): InitFlowResult = build().run()
    }
}
```

- [ ] **Step 4: Implement InitFlowRunner**

Create `startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitFlowRunner.kt`:

```kotlin
package com.debugtools.startupinit

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class InitFlowRunner internal constructor(
    private val tasks: List<InitTask>,
    private val reporter: InitReporter
) {
    suspend fun run(): InitFlowResult = coroutineScope {
        val graph = InitGraph(tasks)
        val validation = graph.validate()
        if (!validation.valid) {
            val reason = validation.error ?: "Invalid init graph"
            reporter.flowStarted(tasks.size)
            reporter.flowFailed(reason)
            reporter.flowCompleted()
            return@coroutineScope InitFlowResult(
                taskResults = listOf(InitTaskResult("init_flow_invalid_graph", InitTaskStatus.FAILED, reason)),
                success = false
            )
        }

        reporter.flowStarted(tasks.size)
        val results = linkedMapOf<String, InitTaskResult>()
        val started = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val failedOrSkipped = mutableSetOf<String>()

        while (results.size < tasks.size) {
            val blocked = graph.blockedByFailures(
                failedOrSkipped = failedOrSkipped,
                completed = completed,
                alreadyResolved = results.keys
            )
            for ((task, failedDependencies) in blocked) {
                reporter.taskSkipped(task.name, failedDependencies)
                results[task.name] = InitTaskResult(
                    name = task.name,
                    status = InitTaskStatus.SKIPPED,
                    error = "Skipped because dependencies failed: ${failedDependencies.joinToString(",")}"
                )
                failedOrSkipped += task.name
            }

            val ready = graph.readyTasks(completed, failedOrSkipped, started)
            if (ready.isEmpty()) break

            ready.forEach { started += it.name }
            val batch = ready.map { task ->
                async {
                    reporter.taskStarted(task.name, task.dependsOn)
                    try {
                        task.runner()
                        reporter.taskSucceeded(task.name)
                        InitTaskResult(task.name, InitTaskStatus.SUCCESS)
                    } catch (t: Throwable) {
                        reporter.taskFailed(task.name, t)
                        InitTaskResult(task.name, InitTaskStatus.FAILED, t.message ?: t.javaClass.simpleName)
                    }
                }
            }.map { it.await() }

            for (result in batch) {
                results[result.name] = result
                when (result.status) {
                    InitTaskStatus.SUCCESS -> completed += result.name
                    InitTaskStatus.FAILED,
                    InitTaskStatus.SKIPPED -> failedOrSkipped += result.name
                }
            }
        }

        reporter.flowCompleted()
        val ordered = tasks.mapNotNull { results[it.name] }
        InitFlowResult(
            taskResults = ordered,
            success = ordered.all { it.status == InitTaskStatus.SUCCESS }
        )
    }
}
```

- [ ] **Step 5: Run scheduler tests to verify GREEN**

Run:

```bash
./gradlew :startup-init-flow:test --tests com.debugtools.startupinit.InitFlowRunnerTest
```

Expected: PASS.

- [ ] **Step 6: Run all core tests**

Run:

```bash
./gradlew :startup-init-flow:test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add startup-init-flow/src/main/kotlin/com/debugtools/startupinit/StartupInitFlow.kt startup-init-flow/src/main/kotlin/com/debugtools/startupinit/InitFlowRunner.kt startup-init-flow/src/test/kotlin/com/debugtools/startupinit/InitFlowRunnerTest.kt
git commit -m "feat: run startup init flow tasks"
```

---

### Task 4: Optional DebugTools Startup Bridge

**Files:**
- Create: `debugtools-startup-init/build.gradle.kts`
- Create: `debugtools-startup-init/src/main/AndroidManifest.xml`
- Create: `debugtools-startup-init/src/main/kotlin/com/debugtools/startupinit/debugtools/StartupMonitorReporter.kt`
- Create: `debugtools-startup-init/src/main/kotlin/com/debugtools/startupinit/debugtools/StartupInitFlowDebugToolsExt.kt`
- Create: `debugtools-startup-init/src/test/kotlin/com/debugtools/startupinit/debugtools/StartupMonitorReporterTest.kt`

**Interfaces:**
- Consumes:
  - `InitReporter`
  - `StartupInitFlow.Builder.reporter(reporter: InitReporter): StartupInitFlow.Builder`
  - `AppStartupMonitor.begin/success/fail/complete`
- Produces:
  - `class StartupMonitorReporter : InitReporter`
  - `fun StartupInitFlow.Builder.reportToStartupMonitor(): StartupInitFlow.Builder`

- [ ] **Step 1: Write bridge tests**

Create `debugtools-startup-init/src/test/kotlin/com/debugtools/startupinit/debugtools/StartupMonitorReporterTest.kt`:

```kotlin
package com.debugtools.startupinit.debugtools

import com.debugtools.startup.AppStartupMonitor
import com.debugtools.startupinit.StartupInitFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupMonitorReporterTest {
    @Test
    fun `bridge records task events into AppStartupMonitor`() = runTest {
        AppStartupMonitor.begin("bridge_test_reset")
        AppStartupMonitor.success("bridge_test_reset")

        val result = StartupInitFlow.builder()
            .task("config") { runBlockingInit { } }
            .task("asr", dependsOn = listOf("config")) { runBlockingInit { } }
            .reportToStartupMonitor()
            .run()

        val session = AppStartupMonitor.currentSession()
        assertTrue(result.success)
        assertEquals(listOf("config", "asr"), session!!.steps.takeLast(2).map { it.name })
        assertTrue(session.steps.first { it.name == "asr" }.dependsOn.contains("config"))
    }

    @Test
    fun `bridge records skipped task as synthetic failed step`() = runTest {
        val result = StartupInitFlow.builder()
            .task("config") { runBlockingInit { error("boom") } }
            .task("asr", dependsOn = listOf("config")) { runBlockingInit { } }
            .reportToStartupMonitor()
            .run()

        val session = AppStartupMonitor.currentSession()
        assertTrue(!result.success)
        assertTrue(session!!.steps.any { it.name == "init_flow_skipped:asr" && it.error!!.contains("config") })
    }
}
```

- [ ] **Step 2: Run bridge tests to verify RED**

Run:

```bash
./gradlew :debugtools-startup-init:testDebugUnitTest --tests com.debugtools.startupinit.debugtools.StartupMonitorReporterTest
```

Expected: FAIL because the bridge module or `reportToStartupMonitor` does not exist.

- [ ] **Step 3: Add bridge module build files**

Create `debugtools-startup-init/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debugtools.startupinit.debugtools"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":startup-init-flow"))
    implementation(project(":debugtools-startup"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
```

Create `debugtools-startup-init/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: Implement bridge reporter and extension**

Create `debugtools-startup-init/src/main/kotlin/com/debugtools/startupinit/debugtools/StartupMonitorReporter.kt`:

```kotlin
package com.debugtools.startupinit.debugtools

import com.debugtools.startup.AppStartupMonitor
import com.debugtools.startupinit.InitReporter

class StartupMonitorReporter : InitReporter {
    override fun taskStarted(name: String, dependsOn: List<String>) {
        AppStartupMonitor.begin(name, dependsOn)
    }

    override fun taskSucceeded(name: String) {
        AppStartupMonitor.success(name)
    }

    override fun taskFailed(name: String, error: Throwable) {
        AppStartupMonitor.fail(name, error)
    }

    override fun taskSkipped(name: String, failedDependencies: List<String>) {
        val synthetic = "init_flow_skipped:$name"
        AppStartupMonitor.begin(synthetic, failedDependencies)
        AppStartupMonitor.fail(
            synthetic,
            "Skipped because dependencies failed: ${failedDependencies.joinToString(",")}"
        )
    }

    override fun flowFailed(reason: String) {
        AppStartupMonitor.begin("init_flow_invalid_graph")
        AppStartupMonitor.fail("init_flow_invalid_graph", reason)
    }

    override fun flowCompleted() {
        AppStartupMonitor.complete()
    }
}
```

Create `debugtools-startup-init/src/main/kotlin/com/debugtools/startupinit/debugtools/StartupInitFlowDebugToolsExt.kt`:

```kotlin
package com.debugtools.startupinit.debugtools

import com.debugtools.startupinit.StartupInitFlow

fun StartupInitFlow.Builder.reportToStartupMonitor(): StartupInitFlow.Builder =
    reporter(StartupMonitorReporter())
```

- [ ] **Step 5: Run bridge tests to verify GREEN**

Run:

```bash
./gradlew :debugtools-startup-init:testDebugUnitTest --tests com.debugtools.startupinit.debugtools.StartupMonitorReporterTest
```

Expected: PASS.

- [ ] **Step 6: Verify core still has no DebugTools dependency**

Run:

```bash
rg -n "com\\.debugtools\\.(startup|core|network|conversation|audiomon|perfmon|general|stability)" startup-init-flow/src || true
```

Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add debugtools-startup-init
git commit -m "feat: bridge startup init flow to startup monitor"
```

---

### Task 5: Sample App Integration

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/com/debugtools/sample/SampleApplication.kt`

**Interfaces:**
- Consumes:
  - `StartupInitFlow.builder()`
  - `reportToStartupMonitor()`
  - `AppStartupMonitor.init(context, appVersion)`
- Produces: sample startup session generated through the new flow.

- [ ] **Step 1: Add app dependency**

Modify `app/build.gradle.kts` dependencies:

```kotlin
implementation(project(":debugtools-startup-init"))
```

- [ ] **Step 2: Replace manual startup tracking in SampleApplication**

Modify `app/src/main/kotlin/com/debugtools/sample/SampleApplication.kt` to:

```kotlin
package com.debugtools.sample

import android.app.Application
import com.debugtools.startup.AppStartupMonitor
import com.debugtools.startupinit.StartupInitFlow
import com.debugtools.startupinit.debugtools.reportToStartupMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SampleApplication : Application() {
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppStartupMonitor.init(this, appVersion = "1.0")

        initScope.launch {
            StartupInitFlow.builder()
                .task("config") { runBlockingInit { Thread.sleep(20) } }
                .task("net") { runBlockingInit { Thread.sleep(15) } }
                .task("asr", dependsOn = listOf("config")) { runBlockingInit { Thread.sleep(70) } }
                .task("nlu", dependsOn = listOf("config")) {
                    runBlockingInit {
                        Thread.sleep(10)
                        throw IllegalStateException("模型文件缺失")
                    }
                }
                .task("tts", dependsOn = listOf("asr")) { runBlockingInit { Thread.sleep(25) } }
                .reportToStartupMonitor()
                .run()
        }
    }
}
```

- [ ] **Step 3: Build sample app**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/com/debugtools/sample/SampleApplication.kt
git commit -m "feat: use startup init flow in sample app"
```

---

### Task 6: Documentation and Full Verification

**Files:**
- Modify: `docs/INTEGRATION.md`

**Interfaces:**
- Consumes: public APIs from Tasks 1-5.
- Produces: user-facing integration instructions for standalone and DebugTools-bridged usage.

- [ ] **Step 1: Add integration documentation**

Add a section after “启动链路接入” in `docs/INTEGRATION.md`:

````markdown
## 应用初始化流程编排

如果业务希望用一套声明式流程管理应用初始化，可以接入 `startup-init-flow`。它不依赖 DebugTools；即使正式包不包含 DebugTools，也能正常执行初始化任务。

独立使用：

```kotlin
StartupInitFlow.builder()
    .task("config") { runBlockingInit { initConfig() } }
    .task("asr", dependsOn = listOf("config")) { suspendInit { initAsr() } }
    .task("tts", dependsOn = listOf("asr")) { callbackInit { done -> initTts { done(it) } } }
    .run()
```

接入 DebugTools 启动链路：

```kotlin
AppStartupMonitor.init(this, appVersion = BuildConfig.VERSION_NAME)

StartupInitFlow.builder()
    .task("config") { runBlockingInit { initConfig() } }
    .task("asr", dependsOn = listOf("config")) { suspendInit { initAsr() } }
    .reportToStartupMonitor()
    .run()
```

依赖语义：

- 依赖全部成功后，任务才会执行。
- 无依赖或依赖已满足的任务会并发执行。
- 某个任务失败后，依赖它的后续任务会标记为 `SKIPPED`。
- 与失败分支无关的任务继续执行。
- 接入 `debugtools-startup-init` 后，任务结果会进入“启动链路”Tab 和全局录制报告。
````

- [ ] **Step 2: Run core tests**

Run:

```bash
./gradlew :startup-init-flow:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run bridge tests**

Run:

```bash
./gradlew :debugtools-startup-init:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run sample build**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verify core module remains independent**

Run:

```bash
rg -n "com\\.debugtools\\.(startup|core|network|conversation|audiomon|perfmon|general|stability)" startup-init-flow/src || true
```

Expected: no output.

- [ ] **Step 6: Run diff check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add docs/INTEGRATION.md
git commit -m "docs: document startup init flow integration"
```
