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
