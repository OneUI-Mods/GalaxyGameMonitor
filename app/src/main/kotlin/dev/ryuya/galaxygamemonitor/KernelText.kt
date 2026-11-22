package dev.ryuya.galaxygamemonitor

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.SequenceInputStream

// Scalar sysfs nodes normally fit in a few bytes. Keep the full read for larger records.
internal fun File.readKernelText(bufferSize: Int = 128): String? = try {
    inputStream().use { input ->
        val prefix = ByteArray(bufferSize)
        val length = input.readNBytes(prefix, 0, prefix.size)
        if (length < prefix.size) prefix.decodeToString(0, length)
        else SequenceInputStream(ByteArrayInputStream(prefix), input).reader().use { it.readText() }
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}
