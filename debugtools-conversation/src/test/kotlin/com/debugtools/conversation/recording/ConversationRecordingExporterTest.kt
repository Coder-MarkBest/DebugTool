package com.debugtools.conversation.recording

import com.debugtools.conversation.trace.TraceEventType
import com.debugtools.conversation.trace.VoiceTraceEvent
import com.debugtools.conversation.trace.VoiceTraceRecorder
import com.debugtools.conversation.trace.voiceTraceProfile
import com.debugtools.core.recording.RecordingContext
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConversationRecordingExporterTest {
    @Test
    fun `export writes raw events and analyzed requests`() {
        val profile = voiceTraceProfile {
            stage("ASR") {
                begin = "AsrBegin"
                end = "AsrEnd"
                includeInDuration = true
                showInConversation = true
            }
        }
        val recorder = VoiceTraceRecorder(profile)
        recorder.record(VoiceTraceEvent("req1", "AsrBegin", TraceEventType.BEGIN, 100, 100))
        recorder.record(VoiceTraceEvent("req1", "AsrEnd", TraceEventType.END, 200, 200))
        val dir = Files.createTempDirectory("conversation-recording").toFile()
        val context = RecordingContext("r1", 1, 1, dir)

        val result = ConversationRecordingExporter(profile, recorder).export(context)

        assertTrue(dir.resolve("conversation/raw-events.json").exists())
        assertTrue(dir.resolve("conversation/requests.json").exists())
        assertTrue(result.files.any { it.name == "raw-events.json" })
        assertTrue(result.files.any { it.name == "requests.json" })
    }
}
