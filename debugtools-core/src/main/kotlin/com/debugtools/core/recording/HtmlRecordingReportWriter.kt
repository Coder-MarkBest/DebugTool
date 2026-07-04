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
                th, td { border-bottom: 1px solid #2f3b46; padding: 8px; text-align: left; font-size: 13px; }
                .critical { color: #ff6b6b; }
                .warning { color: #ffd166; }
                .info { color: #8ecae6; }
                .muted { color: #9aa7b2; }
              </style>
            </head>
            <body>
              <header>
                <h1>DebugTools Recording Report</h1>
                <div class="muted">Recording ${escape(report.context.recordingId)} · ${report.endedAtWallMs - report.context.startedAtWallMs}ms</div>
              </header>
              <main>
                ${overview(report, issues)}
                ${artifactSection("Voice Requests")}
                ${artifactSection("Startup")}
                ${artifactSection("Network")}
                ${artifactSection("Performance")}
                ${artifactSection("Audio")}
                ${artifactSection("Stability")}
                ${issuesSection(issues)}
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
          <div class="muted">See adjacent JSON artifacts for raw module data.</div>
        </section>
    """.trimIndent()

    private fun issuesSection(issues: List<RecordingIssue>) = """
        <section>
          <h2>Issues</h2>
          <table>
            <tr><th>Severity</th><th>Module</th><th>Type</th><th>Detail</th></tr>
            ${issues.joinToString("\n") { issue ->
                val cls = issue.severity.name.lowercase()
                "<tr><td class=\"$cls\">$cls</td><td>${escape(issue.moduleId ?: "")}</td><td>${escape(issue.type)}</td><td>${escape(issue.detail)}</td></tr>"
            }}
          </table>
        </section>
    """.trimIndent()

    private fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
