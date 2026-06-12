package com.debugtools.okhttp.widget

import com.debugtools.okhttp.view.widget.JsonPrettyPrinter
import org.junit.Assert.*
import org.junit.Test

class JsonPrettyPrinterTest {

    @Test fun `pretty prints valid JSON object`() {
        val input = """{"a":1,"b":"x"}"""
        val out = JsonPrettyPrinter.tryFormat(input, contentType = "application/json")
        assertNotNull(out)
        assertTrue(out!!.contains("\n"))
        assertTrue(out.contains("\"a\""))
    }

    @Test fun `pretty prints valid JSON array`() {
        val input = """[1,2,3]"""
        val out = JsonPrettyPrinter.tryFormat(input, contentType = "application/json")
        assertNotNull(out)
        assertTrue(out!!.contains("\n"))
    }

    @Test fun `returns null for non-JSON content type`() {
        val out = JsonPrettyPrinter.tryFormat("hello", contentType = "text/plain")
        assertNull(out)
    }

    @Test fun `returns null when content-type missing and body not JSON-like`() {
        val out = JsonPrettyPrinter.tryFormat("hello world", contentType = null)
        assertNull(out)
    }

    @Test fun `formats when content-type missing but body starts with brace`() {
        val out = JsonPrettyPrinter.tryFormat("""{"k":"v"}""", contentType = null)
        assertNotNull(out)
    }

    @Test fun `returns null on malformed JSON`() {
        val out = JsonPrettyPrinter.tryFormat("""{"a":}""", contentType = "application/json")
        assertNull(out)
    }
}
