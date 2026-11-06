package io.nebula.mobile.patch

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Raised for every condition the patcher refuses to paper over. */
class PatchException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RewriteReport(
    val entryCount: Int,
    val storedEntries: Int,
    val alignedEntries: Int,
    val replacedEntries: List<String>,
    val droppedEntries: List<String>,
)

/**
 * Rewrites an APK (a ZIP) entry by entry.
 *
 * This exists because patching a real APK means replacing one entry while keeping
 * every other byte-level property that Android's installer checks: the original
 * compression method, native libraries stored uncompressed and page aligned, and
 * `resources.arsc` stored and 4-byte aligned. `java.util.zip.ZipOutputStream` cannot
 * express any of that, so the container is written directly here.
 */
class ApkRewriter(private val source: ZipFile) {

    fun rewrite(
        target: File,
        replacements: Map<String, ByteArray>,
        drop: (String) -> Boolean = { false },
    ): RewriteReport {
        val entries = source.entries().toList().filter { !it.isDirectory }
        var stored = 0
        var aligned = 0
        val replaced = ArrayList<String>()
        val dropped = ArrayList<String>()
        val directory = ArrayList<CentralEntry>()

        target.parentFile?.mkdirs()
        val counter = CountingOutput(BufferedOutputStream(FileOutputStream(target), 1 shl 16))
        val scratch = File.createTempFile("nebula-entry", ".bin")

        try {
            for (entry in entries) {
                val name = entry.name
                if (drop(name)) {
                    dropped.add(name)
                    continue
                }
                val replacement = replacements[name]
                if (replacement != null) replaced.add(name)

                val wantsDeflate = entry.method == ZipEntry.DEFLATED
                val staged = stage(scratch, replacement, entry, wantsDeflate)
                if (staged.method == ZipEntry.STORED) stored++

                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val (time, date) = dosTime(entry.time)
                val alignment = alignmentFor(name, staged.method)
                val extra = paddingExtra(counter.written, nameBytes.size, alignment)
                if (extra.isNotEmpty()) aligned++

                val offset = counter.written
                counter.u32(LOCAL_HEADER)
                counter.u16(20)
                counter.u16(UTF8_FLAG)
                counter.u16(staged.method)
                counter.u16(time)
                counter.u16(date)
                counter.u32(staged.crc)
                counter.u32(staged.compressedSize)
                counter.u32(staged.size)
                counter.u16(nameBytes.size)
                counter.u16(extra.size)
                counter.bytes(nameBytes)
                if (extra.isNotEmpty()) counter.bytes(extra)
                writePayload(counter, staged.file, staged.compressedSize)

                directory.add(
                    CentralEntry(
                        name = nameBytes,
                        method = staged.method,
                        crc = staged.crc,
                        compressedSize = staged.compressedSize,
                        size = staged.size,
                        time = time,
                        date = date,
                        offset = offset,
                    ),
                )
            }

            val directoryOffset = counter.written
            for (item in directory) {
                counter.u32(CENTRAL_HEADER)
                counter.u16(20)
                counter.u16(20)
                counter.u16(UTF8_FLAG)
                counter.u16(item.method)
                counter.u16(item.time)
                counter.u16(item.date)
                counter.u32(item.crc)
                counter.u32(item.compressedSize)
                counter.u32(item.size)
                counter.u16(item.name.size)
                counter.u16(0)
                counter.u16(0)
                counter.u16(0)
                counter.u16(0)
                counter.u32(0)
                counter.u32(item.offset)
                counter.bytes(item.name)
            }
            val directorySize = counter.written - directoryOffset

            counter.u32(END_OF_CENTRAL_DIRECTORY)
            counter.u16(0)
            counter.u16(0)
            counter.u16(directory.size)
            counter.u16(directory.size)
            counter.u32(directorySize)
            counter.u32(directoryOffset)
            counter.u16(0)
            counter.flush()
        } finally {
            counter.close()
            scratch.delete()
        }

        return RewriteReport(
            entryCount = directory.size,
            storedEntries = stored,
            alignedEntries = aligned,
            replacedEntries = replaced,
            droppedEntries = dropped,
        )
    }

    private data class CentralEntry(
        val name: ByteArray,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val time: Int,
        val date: Int,
        val offset: Long,
    )

    private class Staged(
        val file: File,
        val method: Int,
        val crc: Long,
        val size: Long,
        val compressedSize: Long,
    )

    private fun stage(scratch: File, replacement: ByteArray?, entry: java.util.zip.ZipEntry, deflate: Boolean): Staged {
        val input: InputStream =
            if (replacement != null) replacement.inputStream() else source.getInputStream(entry)
        val crc = CRC32()
        var size = 0L
        val out = BufferedOutputStream(FileOutputStream(scratch), 1 shl 16)
        try {
            if (deflate) {
                val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
                val buffer = ByteArray(1 shl 16)
                val packed = ByteArray(1 shl 16)
                try {
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        crc.update(buffer, 0, read)
                        size += read
                        deflater.setInput(buffer, 0, read)
                        while (!deflater.needsInput()) {
                            val n = deflater.deflate(packed)
                            if (n > 0) out.write(packed, 0, n)
                        }
                    }
                    deflater.finish()
                    while (!deflater.finished()) {
                        val n = deflater.deflate(packed)
                        if (n > 0) out.write(packed, 0, n)
                    }
                } finally {
                    deflater.end()
                }
            } else {
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    crc.update(buffer, 0, read)
                    size += read
                    out.write(buffer, 0, read)
                }
            }
        } finally {
            out.close()
            if (replacement == null) input.close()
        }
        return Staged(
            file = scratch,
            method = if (deflate) ZipEntry.DEFLATED else ZipEntry.STORED,
            crc = crc.value,
            size = size,
            compressedSize = scratch.length(),
        )
    }

    private fun writePayload(out: CountingOutput, file: File, length: Long) {
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                out.bytes(buffer, read)
                remaining -= read
            }
        }
    }

    companion object {
        private const val LOCAL_HEADER = 0x04034b50L
        private const val CENTRAL_HEADER = 0x02014b50L
        private const val END_OF_CENTRAL_DIRECTORY = 0x06054b50L
        private const val UTF8_FLAG = 0x0800

        /** Android requires these entries stored; native libraries additionally page aligned. */
        fun alignmentFor(name: String, method: Int): Int {
            if (method != ZipEntry.STORED) return 1
            return when {
                name.endsWith(".so") -> 4096
                name.endsWith(".dex") -> 4
                name == "resources.arsc" -> 4
                else -> 4
            }
        }

        /**
         * Padding lives in a real extra field (id 0xD935, the same one zipalign writes)
         * so that every part of the container stays a legal ZIP structure.
         */
        private fun paddingExtra(offset: Long, nameLength: Int, alignment: Int): ByteArray {
            if (alignment <= 1) return ByteArray(0)
            val dataStart = offset + 30 + nameLength
            var remainder = ((alignment - (dataStart % alignment)) % alignment).toInt()
            if (remainder == 0) return ByteArray(0)
            if (remainder < 4) remainder += alignment
            val extra = ByteArray(remainder)
            extra[0] = 0x35
            extra[1] = 0xD9.toByte()
            val payload = remainder - 4
            extra[2] = (payload and 0xFF).toByte()
            extra[3] = ((payload ushr 8) and 0xFF).toByte()
            return extra
        }

        private fun dosTime(epochMillis: Long): Pair<Int, Int> {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = if (epochMillis > 0) epochMillis else System.currentTimeMillis()
            }
            val year = (calendar.get(Calendar.YEAR) - 1980) and 0x7F
            val month = (calendar.get(Calendar.MONTH) + 1) and 0x0F
            val day = calendar.get(Calendar.DAY_OF_MONTH) and 0x1F
            val hour = calendar.get(Calendar.HOUR_OF_DAY) and 0x1F
            val minute = calendar.get(Calendar.MINUTE) and 0x3F
            val second = (calendar.get(Calendar.SECOND) / 2) and 0x1F
            val time = (hour shl 11) or (minute shl 5) or second
            val date = (year shl 9) or (month shl 5) or day
            return time to date
        }
    }

    /** Little-endian writer that tracks the exact byte offset (alignment depends on it). */
    private class CountingOutput(private val out: OutputStream) {
        var written: Long = 0
            private set

        fun u16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            written += 2
        }

        fun u32(value: Long) {
            out.write((value and 0xFF).toInt())
            out.write(((value ushr 8) and 0xFF).toInt())
            out.write(((value ushr 16) and 0xFF).toInt())
            out.write(((value ushr 24) and 0xFF).toInt())
            written += 4
        }

        fun bytes(data: ByteArray, length: Int = data.size) {
            out.write(data, 0, length)
            written += length
        }

        fun flush() = out.flush()

        fun close() = out.close()
    }
}
