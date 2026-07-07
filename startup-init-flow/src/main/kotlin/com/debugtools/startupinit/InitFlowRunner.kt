package com.debugtools.startupinit

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
                taskResults = listOf(
                    InitTaskResult(
                        name = "init_flow_invalid_graph",
                        status = InitTaskStatus.FAILED,
                        error = reason
                    )
                ),
                success = false
            )
        }

        reporter.flowStarted(tasks.size)

        val results = linkedMapOf<String, InitTaskResult>()
        val started = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val failedOrSkipped = mutableSetOf<String>()
        val resultChannel = Channel<InitTaskResult>(capacity = Channel.UNLIMITED)
        var activeTasks = 0

        fun resolveBlockedTasks() {
            do {
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
            } while (blocked.isNotEmpty())
        }

        fun launchReadyTasks() {
            val ready = graph.readyTasks(
                completed = completed,
                failedOrSkipped = failedOrSkipped,
                started = started
            )
            ready.forEach { task ->
                started += task.name
                activeTasks += 1
                launch {
                    reporter.taskStarted(task.name, task.dependsOn)
                    val result = try {
                        task.runner()
                        reporter.taskSucceeded(task.name)
                        InitTaskResult(task.name, InitTaskStatus.SUCCESS)
                    } catch (t: Throwable) {
                        reporter.taskFailed(task.name, t)
                        InitTaskResult(
                            task.name,
                            InitTaskStatus.FAILED,
                            t.message ?: t.javaClass.simpleName
                        )
                    }
                    resultChannel.send(result)
                }
            }
        }

        while (results.size < tasks.size) {
            resolveBlockedTasks()
            launchReadyTasks()
            if (results.size == tasks.size) break
            if (activeTasks == 0) break

            val result = resultChannel.receive()
            activeTasks -= 1
            results[result.name] = result
            when (result.status) {
                InitTaskStatus.SUCCESS -> completed += result.name
                InitTaskStatus.FAILED,
                InitTaskStatus.SKIPPED -> failedOrSkipped += result.name
            }
        }

        resultChannel.close()
        reporter.flowCompleted()
        val ordered = tasks.mapNotNull { results[it.name] }
        InitFlowResult(
            taskResults = ordered,
            success = ordered.all { it.status == InitTaskStatus.SUCCESS }
        )
    }
}
