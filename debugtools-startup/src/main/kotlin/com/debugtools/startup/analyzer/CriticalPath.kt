package com.debugtools.startup.analyzer

import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep

/**
 * The dependency chain that determined total startup time: start from the
 * latest-finishing step and walk back, each time choosing the dependency that
 * finished latest, until a step with no (resolvable) dependency.
 */
object CriticalPath {
    fun of(session: StartupSession): List<String> {
        val byName = session.steps.associateBy { it.name }
        fun endOf(s: StartupStep): Long = s.endUptimeMs ?: s.startUptimeMs
        val terminal = session.steps.maxByOrNull { endOf(it) } ?: return emptyList()

        val chain = ArrayDeque<String>()
        var current: StartupStep? = terminal
        val seen = HashSet<String>()
        while (current != null && seen.add(current.name)) {
            chain.addFirst(current.name)
            val next = current.dependsOn
                .mapNotNull { byName[it] }
                .maxByOrNull { endOf(it) }
            current = next
        }
        return chain.toList()
    }
}
