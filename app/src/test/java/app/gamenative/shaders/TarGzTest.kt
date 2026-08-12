package app.gamenative.shaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class TarGzTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tarEntry(name: String, content: ByteArray): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0, 0, minOf(name.length, 100))
        "0000644\u0000".toByteArray().copyInto(header, 100)
        "0000000\u0000".toByteArray().copyInto(header, 108)
        "0000000\u0000".toByteArray().copyInto(header, 116)
        val sizeOctal = content.size.toString(8).padStart(11, '0') + "\u0000"
        sizeOctal.toByteArray().copyInto(header, 124)
        "00000000000\u0000".toByteArray().copyInto(header, 136)
        // checksum left as spaces (reader does not validate it)
        header[156] = '0'.code.toByte()
        "ustar\u0000".toByteArray().copyInto(header, 257)

        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(content)
        val pad = (512 - content.size % 512) % 512
        out.write(ByteArray(pad))
        return out.toByteArray()
    }

    private fun tarGz(vararg entries: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        entries.forEach { body.write(it) }
        body.write(ByteArray(1024)) // two zero blocks = end of archive
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz -> gz.write(body.toByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun `extracts only wanted files with correct content`() {
        val data = tarGz(
            tarEntry("crt/easymode.slang", "uniform vec4 stuff;".toByteArray()),
            tarEntry("misc/other.slang", "skipped".toByteArray()),
            tarEntry("include/foo.h", "header".toByteArray()),
        )
        val dest = tmp.newFolder("out")
        val seen = mutableListOf<String>()
        TarGz.extract(ByteArrayInputStream(data), dest, setOf("crt/easymode.slang", "include/foo.h")) { seen.add(it) }

        assertTrue(java.io.File(dest, "crt/easymode.slang").isFile)
        assertEquals(
            "uniform vec4 stuff;",
            java.io.File(dest, "crt/easymode.slang").readText(),
        )
        assertTrue(java.io.File(dest, "include/foo.h").isFile)
        // Unwanted entry was skipped entirely.
        assertFalse(java.io.File(dest, "misc/other.slang").exists())
        assertEquals(listOf("crt/easymode.slang", "include/foo.h"), seen)
    }

    @Test
    fun `rejects path traversal`() {
        val data = tarGz(tarEntry("../evil.slang", "boom".toByteArray()))
        val dest = tmp.newFolder("out")
        val threw = runCatching { TarGz.extract(ByteArrayInputStream(data), dest, setOf("../evil.slang")) }
        assertTrue(threw.isFailure)
        assertTrue(threw.exceptionOrNull() is IOException)
        assertFalse(java.io.File(dest.parentFile, "evil.slang").exists())
    }
}
