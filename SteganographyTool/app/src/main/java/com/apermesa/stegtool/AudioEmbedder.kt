package com.apermesa.stegtool

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Embeds and extracts arbitrary files using ID3v2.4 GEOB frames, using the
 * same "obj/e621" MIME sentinel as the Python reference tool (mutagen).
 *
 * No third-party library — pure Kotlin byte manipulation.
 */
object AudioEmbedder {

    private const val MIME_SENTINEL = "obj/e621"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reads each URI in [uris], wraps all of them as GEOB frames in a single
     * ID3v2.4 tag over a silent MP3 shell, and writes the result to
     * Downloads/File2Audio/.  Single-file output name is unchanged;
     * multi-file output name is "<first_basename> 等N个文件 (embedded).mp3".
     */
    fun embedFiles(context: Context, uris: List<Uri>): File {
        val silentRaw = context.resources.openRawResource(R.raw.silent).use { it.readBytes() }
        val audioFrames = stripId3(silentRaw)

        val fileInfos = uris.map { uri ->
            val fileName = resolveDisplayName(context, uri)
            val fileBytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            Pair(fileName, fileBytes)
        }

        val frames = fileInfos.map { (name, bytes) -> buildGeobFrame(name, bytes) }
        val id3Tag = buildId3v24Tag(*frames.toTypedArray())

        val ts = System.currentTimeMillis()
        val tempFile = File(context.cacheDir, "embed_tmp_$ts.mp3")
        tempFile.outputStream().use { it.write(id3Tag); it.write(audioFrames) }

        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "File2Audio"
        ).also { it.mkdirs() }
        val firstName = fileInfos.first().first
        val baseName  = firstName.substringBeforeLast('.')
        val outName   = if (uris.size == 1) "$baseName (embedded).mp3"
                        else "$baseName 等${uris.size}个文件 (embedded).mp3"
        val outFile = File(outDir, outName)
        tempFile.copyTo(outFile, overwrite = true)
        tempFile.delete()
        return outFile
    }

    /**
     * Copies the MP3 at [uri] to a temp file, parses all GEOB frames with
     * mime == "obj/e621", writes each one to <externalFilesDir>/extracted/.
     */
    fun extractFiles(context: Context, uri: Uri): List<File> {
        val ts = System.currentTimeMillis()
        val tempFile = File(context.cacheDir, "extract_tmp_$ts.mp3")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tempFile.outputStream().use { input.copyTo(it) }
        }
        val results = mutableListOf<File>()
        try {
            val mp3 = tempFile.readBytes()
            val outDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "File2Audio"
            ).also { it.mkdirs() }
            for ((name, data) in parseGeobFrames(mp3)) {
                val safeName = File(name).name.ifBlank { "extracted_file" }
                val outFile = File(outDir, safeName)
                outFile.writeBytes(data)
                results += outFile
            }
        } finally {
            tempFile.delete()
        }
        return results
    }

    // ── ID3v2.4 writing ───────────────────────────────────────────────────────

    private fun buildGeobFrame(fileName: String, data: ByteArray): ByteArray {
        val mime = MIME_SENTINEL.toByteArray(Charsets.ISO_8859_1)
        val name = fileName.toByteArray(Charsets.UTF_8)
        val desc = fileName.toByteArray(Charsets.UTF_8)

        // Body = enc(1) + mime + NUL + name + NUL + desc + NUL + data
        val bodySize = 1 + mime.size + 1 + name.size + 1 + desc.size + 1 + data.size
        val out = ByteArrayOutputStream(10 + bodySize)

        out.write("GEOB".toByteArray(Charsets.ISO_8859_1))   // frame ID
        out.write(syncsafeEncode(bodySize))                    // size (syncsafe per ID3v2.4)
        out.write(byteArrayOf(0x00, 0x00))                    // flags
        out.write(0x03)                                        // encoding = UTF-8
        out.write(mime); out.write(0x00)                      // MIME + NUL
        out.write(name); out.write(0x00)                      // filename + NUL
        out.write(desc); out.write(0x00)                      // description + NUL
        out.write(data)
        return out.toByteArray()
    }

    private fun buildId3v24Tag(vararg frames: ByteArray): ByteArray {
        val totalSize = frames.sumOf { it.size }
        val out = ByteArrayOutputStream(10 + totalSize)
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(0x04); out.write(0x00)   // version 2.4.0
        out.write(0x00)                     // no flags
        out.write(syncsafeEncode(totalSize))
        frames.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun syncsafeEncode(v: Int): ByteArray = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(),
        ((v shr 14) and 0x7F).toByte(),
        ((v shr 7)  and 0x7F).toByte(),
        (v and 0x7F).toByte()
    )

    // ── ID3v2.x reading ───────────────────────────────────────────────────────

    /** Returns all (filename, data) pairs from GEOB frames with the sentinel MIME. */
    private fun parseGeobFrames(mp3: ByteArray): List<Pair<String, ByteArray>> {
        val results = mutableListOf<Pair<String, ByteArray>>()
        if (mp3.size < 10) return results
        if (mp3[0] != 'I'.code.toByte() ||
            mp3[1] != 'D'.code.toByte() ||
            mp3[2] != '3'.code.toByte()) return results

        val majorVer = mp3[3].toInt() and 0xFF
        val tagSize  = syncsafeDecode(mp3, 6)
        val tagEnd   = minOf(10 + tagSize, mp3.size)

        var pos = 10
        while (pos + 10 <= tagEnd) {
            val frameId = String(mp3, pos, 4, Charsets.ISO_8859_1)
            if (frameId[0] == '\u0000') break  // padding

            val frameSize = if (majorVer >= 4) syncsafeDecode(mp3, pos + 4)
                            else               intDecode(mp3, pos + 4)
            val dataStart = pos + 10
            val dataEnd   = dataStart + frameSize

            if (frameId == "GEOB" && dataEnd <= mp3.size) {
                parseGeobBody(mp3, dataStart, frameSize)?.let { results += it }
            }
            pos = dataEnd
        }
        return results
    }

    private fun parseGeobBody(mp3: ByteArray, start: Int, size: Int): Pair<String, ByteArray>? {
        if (size < 3) return null
        val enc = mp3[start].toInt() and 0xFF
        val wideCharset = enc == 1 || enc == 2

        var pos = start + 1

        // MIME type — always Latin-1 single-byte NUL-terminated
        val mimeEnd = mp3.indexOf1(0x00.toByte(), pos)
        if (mimeEnd < 0) return null
        val mime = String(mp3, pos, mimeEnd - pos, Charsets.ISO_8859_1)
        if (mime != MIME_SENTINEL) return null
        pos = mimeEnd + 1

        // Filename — encoding-aware NUL-terminated
        val charset = when (enc) {
            0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE;   else -> Charsets.UTF_8
        }
        val (filename, afterName) = readNulTerminated(mp3, pos, wideCharset, charset)
        pos = afterName

        // Description — skip it
        val (_, afterDesc) = readNulTerminated(mp3, pos, wideCharset, charset)
        pos = afterDesc

        val dataLen = (start + size) - pos
        if (dataLen < 0) return null
        return Pair(filename, mp3.copyOfRange(pos, pos + dataLen))
    }

    private fun readNulTerminated(
        bytes: ByteArray, start: Int, wide: Boolean,
        charset: java.nio.charset.Charset
    ): Pair<String, Int> {
        return if (wide) {
            var i = start
            while (i + 1 < bytes.size) {
                if (bytes[i] == 0x00.toByte() && bytes[i + 1] == 0x00.toByte())
                    return Pair(String(bytes, start, i - start, charset), i + 2)
                i += 2
            }
            Pair(String(bytes, start, bytes.size - start, charset), bytes.size)
        } else {
            val end = bytes.indexOf1(0x00.toByte(), start)
            if (end < 0) Pair(String(bytes, start, bytes.size - start, charset), bytes.size)
            else         Pair(String(bytes, start, end - start, charset), end + 1)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ByteArray.indexOf1(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }

    private fun syncsafeDecode(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o+1].toInt() and 0x7F) shl 14) or
        ((b[o+2].toInt() and 0x7F) shl 7) or (b[o+3].toInt() and 0x7F)

    private fun intDecode(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o+1].toInt() and 0xFF) shl 16) or
        ((b[o+2].toInt() and 0xFF) shl 8) or (b[o+3].toInt() and 0xFF)

    /** Returns the byte slice after the ID3v2 tag (the actual MP3 audio frames). */
    private fun stripId3(mp3: ByteArray): ByteArray {
        if (mp3.size >= 10 &&
            mp3[0] == 'I'.code.toByte() &&
            mp3[1] == 'D'.code.toByte() &&
            mp3[2] == '3'.code.toByte()) {
            val tagSize   = syncsafeDecode(mp3, 6)
            val audioStart = 10 + tagSize
            if (audioStart < mp3.size) return mp3.copyOfRange(audioStart, mp3.size)
        }
        return mp3
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }
}
