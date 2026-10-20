package com.example.gallery.backup

import java.io.IOException

/**
 * Uploads what isn't in Drive yet, newest first, one file at a time, in chunks, and can pick an interrupted
 * file up where it stopped. It reports nothing to the user: the caller only gets a [RunReport] to log.
 */
class BackupEngine(
    private val source: BackupSource,
    private val ledger: BackupLedger,
    private val transport: DriveTransport,
    private val chunkSize: Int = DEFAULT_CHUNK,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    init {
        require(chunkSize > 0 && chunkSize % 262_144 == 0) { "Drive wants chunks in multiples of 256 KiB" }
    }

    private enum class One { UPLOADED, EXISTED, SKIPPED, NETWORK, CONFIG, TIME_UP, STOPPED }

    fun run(config: BackupConfig, deadlineMs: Long, shouldStop: () -> Boolean = { false }): RunReport {
        val done = ledger.doneKeys()
        val todo = source.list(config.photos, config.videos)
            .filter { it.size > 0 && it.key !in done }
            .sortedWith(compareByDescending<BackupItem> { it.takenMs }.thenByDescending { it.id })

        var uploaded = 0
        var existed = 0
        var skipped = 0
        for (item in todo) {
            val outcome = if (shouldStop()) One.STOPPED else if (clock() >= deadlineMs) One.TIME_UP else backUp(config, item, deadlineMs, shouldStop, true)
            when (outcome) {
                One.UPLOADED -> uploaded++
                One.EXISTED -> existed++
                One.SKIPPED -> skipped++
                One.NETWORK -> return RunReport(RunResult.NETWORK, uploaded, existed, skipped)
                One.CONFIG -> return RunReport(RunResult.CONFIG, uploaded, existed, skipped)
                One.TIME_UP -> return RunReport(RunResult.TIME_UP, uploaded, existed, skipped)
                One.STOPPED -> return RunReport(RunResult.STOPPED, uploaded, existed, skipped)
            }
        }
        return RunReport(RunResult.ALL_DONE, uploaded, existed, skipped)
    }

    private fun backUp(config: BackupConfig, item: BackupItem, deadlineMs: Long, shouldStop: () -> Boolean, mayRestart: Boolean): One {
        val key = item.key
        var session = ledger.session(key)
        var offset = 0L

        if (session != null) {
            when (val s = transport.status(session, item.size)) {
                UploadStatus.Complete -> { finish(key); return One.UPLOADED }
                is UploadStatus.Incomplete -> offset = s.next
                is UploadStatus.Transient -> return One.NETWORK
                UploadStatus.SessionGone, is UploadStatus.Rejected -> { ledger.clearSession(key); session = null }
            }
        }

        if (session == null) {
            when (val i = transport.init(config, item)) {
                InitResult.Exists -> { finish(key); return One.EXISTED }
                is InitResult.Session -> { session = i.uri; ledger.saveSession(key, i.uri); offset = 0 }
                is InitResult.Rejected -> return One.SKIPPED
                is InitResult.ConfigProblem -> return One.CONFIG
                is InitResult.Transient -> return One.NETWORK
            }
        }

        val buffer = ByteArray(minOf(chunkSize.toLong(), item.size).toInt())
        while (true) {
            if (shouldStop()) return One.STOPPED
            if (clock() >= deadlineMs) return One.TIME_UP
            if (offset >= item.size) {
                // Drive says it has every byte but didn't confirm; ask once more.
                return when (transport.status(session, item.size)) {
                    UploadStatus.Complete -> { finish(key); One.UPLOADED }
                    is UploadStatus.Transient -> One.NETWORK
                    else -> { ledger.clearSession(key); One.SKIPPED }
                }
            }
            val want = minOf(buffer.size.toLong(), item.size - offset).toInt()
            if (!readChunk(item, offset, buffer, want)) {
                ledger.clearSession(key)
                return One.SKIPPED // the file changed or vanished while we were reading it
            }
            var result = transport.put(session, item.size, offset, buffer, want)
            if (result is UploadStatus.Transient) {
                // One short pause, then ask Drive how much it really has.
                sleep(RETRY_PAUSE_MS)
                result = when (val s = transport.status(session, item.size)) {
                    is UploadStatus.Incomplete, UploadStatus.Complete -> s
                    else -> return if (s is UploadStatus.Transient) One.NETWORK else restartOrSkip(config, item, deadlineMs, shouldStop, mayRestart)
                }
            }
            when (result) {
                UploadStatus.Complete -> { finish(key); return One.UPLOADED }
                is UploadStatus.Incomplete -> offset = result.next
                UploadStatus.SessionGone -> return restartOrSkip(config, item, deadlineMs, shouldStop, mayRestart)
                is UploadStatus.Rejected -> { ledger.clearSession(key); return One.SKIPPED }
                is UploadStatus.Transient -> return One.NETWORK
            }
        }
    }

    private fun restartOrSkip(config: BackupConfig, item: BackupItem, deadlineMs: Long, shouldStop: () -> Boolean, mayRestart: Boolean): One {
        ledger.clearSession(item.key)
        return if (mayRestart) backUp(config, item, deadlineMs, shouldStop, false) else One.SKIPPED
    }

    private fun finish(key: String) {
        ledger.markDone(key)
        ledger.clearSession(key)
    }

    /** Fills [buffer] with exactly [want] bytes starting at [offset]; false if the file can't supply them. */
    private fun readChunk(item: BackupItem, offset: Long, buffer: ByteArray, want: Int): Boolean = try {
        source.open(item, offset)?.use { ins ->
            var read = 0
            while (read < want) {
                val n = ins.read(buffer, read, want - read)
                if (n < 0) break
                read += n
            }
            read == want
        } ?: false
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    companion object {
        const val DEFAULT_CHUNK = 8 * 1024 * 1024
        private const val RETRY_PAUSE_MS = 2_000L
    }
}
