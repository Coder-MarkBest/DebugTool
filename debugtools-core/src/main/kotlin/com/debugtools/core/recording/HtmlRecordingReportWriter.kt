package com.debugtools.core.recording

import java.io.File

class HtmlRecordingReportWriter {
    fun write(report: RecordingSessionReport, output: File): File {
        output.parentFile?.mkdirs()
        output.writeText(render(report))
        return output
    }

    private fun render(report: RecordingSessionReport): String {
        val issues = report.issues + report.moduleResults.flatMap { it.issues }
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <title>DebugTools Recording Report</title>
              <style>
                body { font-family: system-ui, -apple-system, BlinkMacSystemFont, sans-serif; margin: 0; background: #101418; color: #e6edf3; }
                header { padding: 24px; background: #17202a; border-bottom: 1px solid #2f3b46; }
                main { padding: 20px; display: grid; gap: 16px; }
                section { border: 1px solid #2f3b46; border-radius: 8px; padding: 16px; background: #141a20; }
                h1, h2 { margin: 0 0 12px; }
                table { border-collapse: collapse; width: 100%; }
                th, td { border-bottom: 1px solid #2f3b46; padding: 8px; text-align: left; font-size: 13px; vertical-align: top; }
                .critical { color: #ff6b6b; }
                .warning { color: #ffd166; }
                .info { color: #8ecae6; }
                .muted { color: #9aa7b2; }
                .issue-detail { display: grid; gap: 4px; }
                .label { color: #9aa7b2; font-size: 12px; }
              </style>
            </head>
            <body>
              <header>
                <h1>DebugTools Recording Report</h1>
                <div class="muted">Recording ${escape(report.context.recordingId)} · ${report.endedAtWallMs - report.context.startedAtWallMs}ms</div>
              </header>
              <main>
                ${overview(report, issues)}
                ${issuesSection(issues)}
                ${artifactSection("Raw Artifacts")}
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun overview(report: RecordingSessionReport, issues: List<RecordingIssue>) = """
        <section>
          <h2>Overview</h2>
          <table>
            <tr><th>Saved path</th><td>${escape(report.rootDir.absolutePath)}</td></tr>
            <tr><th>Modules</th><td>${report.moduleResults.size}</td></tr>
            <tr><th>Issues</th><td>${issues.size}</td></tr>
          </table>
        </section>
    """.trimIndent()

    private fun artifactSection(title: String) = """
        <section>
          <h2>${escape(title)}</h2>
          <div class="muted">Voice Requests · Startup · Network · Performance · Audio · Stability</div>
          <div class="muted">Adjacent JSON artifacts keep the raw module data used as evidence.</div>
        </section>
    """.trimIndent()

    private fun issuesSection(issues: List<RecordingIssue>): String {
        val rows = if (issues.isEmpty()) {
            """<tr><td colspan="4" class="muted">No diagnostic issues were reported.</td></tr>"""
        } else {
            issues.sortedWith(
                compareBy<RecordingIssue> {
                    when (it.severity) {
                        RecordingIssueSeverity.CRITICAL -> 0
                        RecordingIssueSeverity.WARNING -> 1
                        RecordingIssueSeverity.INFO -> 2
                    }
                }.thenBy { it.moduleId.orEmpty() }
            ).joinToString("\n") { issue ->
                val cls = issue.severity.name.lowercase()
                """
                    <tr>
                      <td class="$cls">$cls</td>
                      <td>${escape(issue.moduleId ?: "")}</td>
                      <td>${escape(issue.type)}</td>
                      <td>
                        <div class="issue-detail">
                          <div>${escape(issue.detail)}</div>
                          ${optionalLine("Evidence", issue.evidence)}
                          ${optionalLine("Suggestion", issue.suggestion)}
                        </div>
                      </td>
                    </tr>
                """.trimIndent()
            }
        }
        return """
            <section>
              <h2>Diagnostic Issues</h2>
              <table>
                <tr><th>Severity</th><th>Module</th><th>Type</th><th>Diagnosis</th></tr>
                $rows
              </table>
            </section>
        """.trimIndent()
    }

    private fun optionalLine(label: String, value: String): String =
        if (value.isBlank()) {
            ""
        } else {
            """<div><span class="label">$label:</span> ${escape(value)}</div>"""
        }

    private fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
