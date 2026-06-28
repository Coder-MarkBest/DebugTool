package com.debugtools.startup.analyzer

import com.debugtools.startup.protocol.IssueType
import com.debugtools.startup.protocol.Severity
import com.debugtools.startup.protocol.StartupIssue
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep
import com.debugtools.startup.protocol.StepStatus

/** Pure diagnostics over a finished (or finalized) startup session. */
object StartupAnalyzer {

    const val DEFAULT_SLOW_MS = 50L

    fun analyze(session: StartupSession, slowThresholdMs: Long = DEFAULT_SLOW_MS): List<StartupIssue> {
        val issues = mutableListOf<StartupIssue>()
        val byName = session.steps.associateBy { it.name }

        for (s in session.steps) {
            val dur = if (s.endUptimeMs != null) s.endUptimeMs - s.startUptimeMs else null

            if (s.status == StepStatus.FAILED) {
                issues += StartupIssue(IssueType.ERROR, s.name, s.error ?: "初始化失败", Severity.ERROR)
            }
            if (dur != null && dur > slowThresholdMs) {
                issues += StartupIssue(IssueType.SLOW, s.name, "耗时 ${dur}ms", Severity.WARN)
            }
            if (session.completedUptimeMs != null && s.endUptimeMs == null) {
                issues += StartupIssue(IssueType.NEVER_ENDED, s.name, "完成时仍未结束(漏 end/卡死)", Severity.WARN)
            }
            for (depName in s.dependsOn) {
                val dep = byName[depName] ?: continue
                if (dep.endUptimeMs != null && s.startUptimeMs < dep.endUptimeMs) {
                    issues += StartupIssue(
                        IssueType.DEP_VIOLATION, s.name,
                        "在依赖 $depName 完成前就开始(顺序 race)", Severity.WARN
                    )
                }
            }
            if (s.dependsOn.isEmpty() && (s.startUptimeMs - session.launchUptimeMs) > slowThresholdMs) {
                issues += StartupIssue(
                    IssueType.PARALLELIZABLE, s.name,
                    "无依赖却延迟 ${s.startUptimeMs - session.launchUptimeMs}ms 才开始,可提前并行", Severity.INFO
                )
            }
        }

        if (hasCycle(byName)) {
            issues += StartupIssue(IssueType.DEP_CYCLE, null, "dependsOn 存在依赖环", Severity.ERROR)
        }
        return issues
    }

    private fun hasCycle(byName: Map<String, StartupStep>): Boolean {
        val state = HashMap<String, Int>()
        fun dfs(name: String): Boolean {
            when (state[name]) { 1 -> return true; 2 -> return false }
            state[name] = 1
            for (d in byName[name]?.dependsOn ?: emptyList()) {
                if (byName.containsKey(d) && dfs(d)) return true
            }
            state[name] = 2
            return false
        }
        return byName.keys.any { dfs(it) }
    }
}
