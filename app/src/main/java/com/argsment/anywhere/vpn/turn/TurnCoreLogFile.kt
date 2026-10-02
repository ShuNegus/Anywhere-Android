package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.turn.TurnEnvironment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Keeps the Go core's log on disk so it can be pulled off a device after the fact
 * (`adb shell run-as app.anywhere.client cat cache/turn-core.log`). Mirrors iOS
 * `TurnCoreLogFile` (`<app group>/Library/Caches/turn-core.log`): one line per entry,
 * `"<ISO-8601 with millis> <line>"`, rotated at 1 MiB into `turn-core.log.1` (one
 * previous generation kept). Writes happen on one background thread.
 */
object TurnCoreLogFile {

    private const val MAX_BYTES = 1L shl 20

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "turn-core-log").apply { isDaemon = true }
    }

    /** Touched on [executor] only. */
    private var stream: FileOutputStream? = null
    private var openFile: File? = null
    private var size = 0L
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Overrides the location (tests); `null` falls back to [TurnEnvironment.coreLogFile]. */
    @Volatile
    var fileOverride: File? = null

    fun append(line: String) {
        val file = fileOverride ?: TurnEnvironment.coreLogFile ?: return
        val now = Date()
        executor.execute { write(file, line, now) }
    }

    private fun write(file: File, line: String, date: Date) {
        try {
            if (openFile != file) {
                stream?.close()
                stream = null
                openFile = file
            }
            if (stream != null && size >= MAX_BYTES) {
                stream?.close()
                stream = null
                val previous = File(file.path + ".1")
                previous.delete()
                file.renameTo(previous)
            }
            if (stream == null) {
                file.parentFile?.mkdirs()
                stream = FileOutputStream(file, true)
                size = file.length()
            }
            val data = "${stamp.format(date)} $line\n".toByteArray(Charsets.UTF_8)
            stream?.write(data)
            size += data.size
        } catch (_: Exception) {
            stream = null
        }
    }
}
