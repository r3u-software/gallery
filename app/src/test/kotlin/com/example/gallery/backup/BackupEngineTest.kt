package com.example.gallery.backup

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KIB256 = 262_144

private fun item(id: Long, size: Int, taken: Long = id * 1000, kind: MediaKind = MediaKind.IMAGE, name: String = "IMG_$id.jpg") =
    BackupItem(kind, id, name, if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4", size.toLong(), taken, taken, "Camera")

private fun bytes(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it * 31 + seed) and 0xff).toByte() }

internal class FakeSource(val items: List<BackupItem>, val data: MutableMap<String, ByteArray>) : BackupSource {
    var lastFlags: Pair<Boolean, Boolean>? = null
    val realSizes = HashMap<String, Long>()
    override fun sizeOf(item: BackupItem): Long? = realSizes[keyOf(item)] ?: item.size
    private fun keyOf(item: BackupItem): String = items.first { it.id == item.id && it.kind == item.kind }.key
    override fun list(photos: Boolean, videos: Boolean): List<BackupItem> {
        lastFlags = photos to videos
        return items.filter { (it.kind == MediaKind.IMAGE && photos) || (it.kind == MediaKind.VIDEO && videos) }
    }
    override fun open(item: BackupItem, offset: Long): InputStream? =
        data[keyOf(item)]?.let { ByteArrayInputStream(it, offset.toInt(), it.size - offset.toInt()) }
}

internal class FakeLedger : BackupLedger {
    val done = HashSet<String>()
    val sessions = HashMap<String, String>()
    override fun doneKeys(): Set<String> = HashSet(done)
    override fun markDone(key: String) { done.add(key) }
    override fun session(key: String): String? = sessions[key]
    override fun saveSession(key: String, uri: String) { sessions[key] = uri }
    override fun clearSession(key: String) { sessions.remove(key) }
}

/** A tiny in-memory Drive. Failures are scripted by call number. */
internal class FakeDrive : DriveTransport {
    class Slot(val total: Long, val received: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream())

    val slots = HashMap<String, Slot>()
    val existing = HashSet<String>()
    val uploadedNames = ArrayList<String>()
    val putOffsets = ArrayList<Long>()
    var inits = 0
    var puts = 0
    var initOverride: ((BackupItem) -> InitResult?)? = null
    var failPuts = HashSet<Int>()
    var failStatusAfterFailedPut = false
    var offlineFromPut = Int.MAX_VALUE
    var expireSessionsOnStatus = false
    var onPut: () -> Unit = {}
    private var nameBySession = HashMap<String, String>()

    override fun init(config: BackupConfig, item: BackupItem): InitResult {
        inits++
        initOverride?.invoke(item)?.let { return it }
        if (item.name in existing) return InitResult.Exists
        val id = "session-$inits"
        slots[id] = Slot(item.size)
        nameBySession[id] = item.name
        return InitResult.Session(id)
    }

    override fun status(session: String, total: Long): UploadStatus {
        if (puts >= offlineFromPut || (failStatusAfterFailedPut && puts in failPuts)) return UploadStatus.Transient("offline")
        val slot = slots[session]
        if (slot == null || expireSessionsOnStatus) return UploadStatus.SessionGone
        return if (slot.received.size().toLong() == slot.total) UploadStatus.Complete else UploadStatus.Incomplete(slot.received.size().toLong())
    }

    override fun put(session: String, total: Long, offset: Long, data: ByteArray, length: Int): UploadStatus {
        puts++
        onPut()
        if (puts >= offlineFromPut || puts in failPuts) return UploadStatus.Transient("blip")
        val slot = slots[session] ?: return UploadStatus.SessionGone
        putOffsets.add(offset)
        if (offset != slot.received.size().toLong()) return UploadStatus.Incomplete(slot.received.size().toLong())
        slot.received.write(data, 0, length)
        if (slot.received.size().toLong() == slot.total) {
            uploadedNames.add(nameBySession[session]!!)
            return UploadStatus.Complete
        }
        return UploadStatus.Incomplete(slot.received.size().toLong())
    }

    fun contentOf(name: String): ByteArray? =
        nameBySession.entries.firstOrNull { it.value == name }?.let { slots[it.key]?.received?.toByteArray() }
}

class BackupEngineTest {
    private val config = BackupConfig("https://script.google.com/macros/s/x/exec", "secret-secret")
    private val never = Long.MAX_VALUE

    private fun engine(source: BackupSource, ledger: BackupLedger, drive: DriveTransport, chunk: Int = KIB256, clock: () -> Long = { 0L }) =
        BackupEngine(source, ledger, drive, chunk, clock) { }

    private fun world(vararg sizes: Int): Triple<FakeSource, FakeLedger, FakeDrive> {
        val items = sizes.mapIndexed { i, size -> item((i + 1).toLong(), size) }
        val data = HashMap<String, ByteArray>()
        items.forEachIndexed { i, it -> data[it.key] = bytes(it.size.toInt(), i) }
        return Triple(FakeSource(items, data), FakeLedger(), FakeDrive())
    }

    @Test
    fun `uploads everything newest first, byte for byte, and remembers it`() {
        val (source, ledger, drive) = world(1000, 700_000, 5)
        val report = engine(source, ledger, drive).run(config, never)

        assertEquals(RunResult.ALL_DONE, report.result)
        assertEquals(3, report.uploaded)
        assertEquals(listOf("IMG_3.jpg", "IMG_2.jpg", "IMG_1.jpg"), drive.uploadedNames)
        source.items.forEach { assertArrayEquals(source.data[it.key], drive.contentOf(it.name)) }
        assertEquals(3, ledger.done.size)
        assertTrue(ledger.sessions.isEmpty())

        val again = engine(source, ledger, drive).run(config, never)
        assertEquals(0, again.uploaded)
        assertEquals(3, drive.inits) // nothing asked for the second time
    }

    @Test
    fun `big files go up in chunks of the configured size`() {
        val (source, ledger, drive) = world(700_000)
        engine(source, ledger, drive, chunk = KIB256).run(config, never)
        assertEquals(listOf(0L, KIB256.toLong(), 2L * KIB256), drive.putOffsets)
        assertArrayEquals(source.data.values.first(), drive.contentOf("IMG_1.jpg"))
    }

    @Test
    fun `photos and videos can be switched off separately, and empty files are ignored`() {
        val items = listOf(item(1, 100), item(2, 100, kind = MediaKind.VIDEO), item(3, 0))
        val data = items.associate { it.key to bytes(it.size.toInt(), 1) }.toMutableMap()
        val source = FakeSource(items, data)

        val photosOnly = FakeDrive()
        engine(source, FakeLedger(), photosOnly).run(config.copy(videos = false), never)
        assertEquals(listOf("IMG_1.jpg"), photosOnly.uploadedNames)
        assertEquals(true to false, source.lastFlags)

        val videosOnly = FakeDrive()
        engine(source, FakeLedger(), videosOnly).run(config.copy(photos = false), never)
        assertEquals(listOf("IMG_2.jpg"), videosOnly.uploadedNames)
    }

    @Test
    fun `a file Drive already has is marked done without uploading`() {
        val (source, ledger, drive) = world(100, 100)
        drive.existing.add("IMG_2.jpg")
        val report = engine(source, ledger, drive).run(config, never)
        assertEquals(1, report.uploaded)
        assertEquals(1, report.alreadyThere)
        assertEquals(listOf("IMG_1.jpg"), drive.uploadedNames)
        assertEquals(2, ledger.done.size)
    }

    @Test
    fun `a short network blip is recovered inside the same run`() {
        val (source, ledger, drive) = world(700_000)
        drive.failPuts.add(2) // the second chunk fails once; Drive still has the first
        val report = engine(source, ledger, drive).run(config, never)
        assertEquals(RunResult.ALL_DONE, report.result)
        assertEquals(1, report.uploaded)
        assertArrayEquals(source.data.values.first(), drive.contentOf("IMG_1.jpg"))
    }

    @Test
    fun `going offline stops the run, and the next run resumes the same upload`() {
        val (source, ledger, drive) = world(700_000)
        drive.offlineFromPut = 2
        val first = engine(source, ledger, drive).run(config, never)
        assertEquals(RunResult.NETWORK, first.result)
        assertTrue(ledger.done.isEmpty())
        assertEquals(1, ledger.sessions.size) // remembered for later

        drive.offlineFromPut = Int.MAX_VALUE
        drive.putOffsets.clear()
        val second = engine(source, ledger, drive).run(config, never)
        assertEquals(RunResult.ALL_DONE, second.result)
        assertEquals(1, drive.inits) // no new upload slot was asked for
        assertEquals(KIB256.toLong(), drive.putOffsets.first()) // carried on after the first chunk
        assertArrayEquals(source.data.values.first(), drive.contentOf("IMG_1.jpg"))
        assertTrue(ledger.sessions.isEmpty())
    }

    @Test
    fun `an expired upload slot is replaced once`() {
        val (source, ledger, drive) = world(700_000)
        val key = source.items.first().key
        ledger.saveSession(key, "stale-session")
        val report = engine(source, ledger, drive).run(config, never)
        assertEquals(RunResult.ALL_DONE, report.result)
        assertEquals(1, report.uploaded)
        assertEquals(1, drive.inits)
        assertArrayEquals(source.data.values.first(), drive.contentOf("IMG_1.jpg"))
    }

    @Test
    fun `a wrong secret or address stops the run and marks nothing`() {
        val (source, ledger, drive) = world(100, 100)
        drive.initOverride = { InitResult.ConfigProblem("unauthorized") }
        val report = engine(source, ledger, drive).run(config, never)
        assertEquals(RunResult.CONFIG, report.result)
        assertEquals(1, drive.inits)
        assertTrue(ledger.done.isEmpty())
    }

    @Test
    fun `throttling or a full Drive stops the run quietly`() {
        val (source, ledger, drive) = world(100, 100)
        drive.initOverride = { InitResult.Transient("quota") }
        assertEquals(RunResult.NETWORK, engine(source, ledger, drive).run(config, never).result)
        assertTrue(ledger.done.isEmpty())
    }

    @Test
    fun `one rejected file does not block the others`() {
        val (source, ledger, drive) = world(100, 100, 100)
        drive.initOverride = { if (it.name == "IMG_2.jpg") InitResult.Rejected("bad_request") else null }
        val report = engine(source, ledger, drive).run(config, never)
        assertEquals(RunResult.ALL_DONE, report.result)
        assertEquals(2, report.uploaded)
        assertEquals(1, report.skipped)
        assertFalse(ledger.done.contains(source.items[1].key))
    }

    @Test
    fun `a file that changes while it is read is skipped and tried again next time`() {
        val (source, ledger, drive) = world(300_000)
        val key = source.items.first().key
        source.data[key] = bytes(1000, 1) // now shorter than the 300000 bytes the list promised
        val report = engine(source, ledger, drive).run(config, never)
        assertEquals(1, report.skipped)
        assertTrue(ledger.done.isEmpty())
        assertNull(ledger.session(key))
    }

    @Test
    fun `a stale size from MediaStore does not cut the upload short`() {
        val (source, ledger, drive) = world(100)
        val listed = source.items.first()
        val whole = bytes(300_000, 7)
        source.data[listed.key] = whole
        source.realSizes[listed.key] = whole.size.toLong() // the file grew after MediaStore last looked
        val report = engine(source, ledger, drive).run(config, never)
        assertEquals(1, report.uploaded)
        assertArrayEquals(whole, drive.contentOf("IMG_1.jpg"))
        assertTrue(ledger.done.contains(listed.key)) // remembered under the listed key, so it is not redone
        assertEquals(1, ledger.done.size)
    }

    @Test
    fun `an unreadable file is skipped`() {
        val (source, ledger, drive) = world(100, 100)
        source.realSizes[source.items[0].key] = 0L // sizeOf says nothing usable
        val report = engine(source, ledger, drive).run(config, never)
        assertEquals(1, report.uploaded)
        assertEquals(1, report.skipped)
    }

    @Test
    fun `the time budget ends a run but keeps the unfinished upload`() {
        val (source, ledger, drive) = world(700_000, 100)
        var now = 0L
        drive.onPut = { now += 100 }
        val report = BackupEngine(source, ledger, drive, KIB256, { now }) { }.run(config, deadlineMs = 150)
        assertEquals(RunResult.TIME_UP, report.result)
        assertEquals(1, ledger.done.size) // the small, newest file made it
        assertEquals(1, ledger.sessions.size) // the big one is half way and remembered
    }

    @Test
    fun `the stop signal ends a run`() {
        val (source, ledger, drive) = world(100, 100)
        var stop = false
        drive.onPut = { stop = true }
        val report = engine(source, ledger, drive).run(config, never) { stop }
        assertEquals(RunResult.STOPPED, report.result)
        assertEquals(1, report.uploaded)
    }
}
