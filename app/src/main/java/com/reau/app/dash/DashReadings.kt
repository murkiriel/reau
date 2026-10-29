package com.reau.app.dash

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One thing the dash said, as the screen "Dados da moto" keeps it: the last [hex] value of one code, when it came
 * ([atMs], the phone's clock) and how many times it came since REau started. The 0F values (the firmware parts)
 * come to it already decrypted; see [DashReadings.onValue].
 */
data class DashReading(val hex: String, val atMs: Long, val count: Int)

/** What [DashSession] hands over of what the dash says, and the end of a session; [DashReadings] is the real one. */
interface DashReadingSink {
    /** The value [value] of TLV ([type], [sub]); for the 0F group already decrypted (except `0F04`, which never is). */
    fun onValue(type: Int, sub: Int, value: ByteArray)

    /** The session ended: whatever was held back goes to the screen and the storage. */
    fun flush()
}

/**
 * What the dash has told REau, by code (`"0303"` is TLV type 03, sub 03): the live end of the screen "Dados da moto".
 * [DashSession] hands every TLV it hears to [onValue]; the screen reads [all], and [DashReport] turns the hex into words.
 * Kept in the phone between runs ([attach]), so the screen shows the last reading, with its time, while the dash is off.
 *
 * The dash says some things several times a second; the screen is told at most once a second ([PUBLISH_EVERY_MS]) and the
 * phone's storage at most every [SAVE_EVERY_MS], or when the session ends ([flush]).
 * Only the codes [DashReport.isShown] accepts are kept (no VIN, no serial), and the storage is
 * [com.reau.app.data.KeystoreBackedPrefs] (encrypted); nothing here ever reaches the ride log or the diagnostics zip.
 */
object DashReadings : DashReadingSink {
    private val lock = Any()
    private val map = HashMap<String, DashReading>()
    private val state = MutableStateFlow<Map<String, DashReading>>(emptyMap())
    private val settingsState = MutableStateFlow(DashReport.Settings())
    @Volatile private var store: DashReadingsStore? = null
    private var publishedAtMs = 0L
    private var savedAtMs = 0L

    // The codes whose value changed since the last save: only those are written (each write is an encryption by the Keystore).
    private val dirty = HashSet<String>()
    private var unpublished = false

    /**
     * Where the storage work runs: one thread of its own, in order. Saving re-encrypts each value through the Android Keystore
     * (up to 14 of them), and it used to run on the thread that receives the dash's packets, delaying the joystick's replies and
     * the frame acknowledgements (audit of 20/10/2026). A test gives it an executor it runs by hand.
     */
    @Volatile internal var saver: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "dash-readings-save").apply { isDaemon = true }
    }

    /** Everything heard so far, from this run and from the last ones. */
    val all: StateFlow<Map<String, DashReading>> get() = state

    /**
     * The dash's own settings alone: it changes only when a setting does, so a screen that follows it (the theme) is not
     * redrawn at every second the dash speaks, as one that follows [all] would be.
     */
    val settings: StateFlow<DashReport.Settings> get() = settingsState

    /** Takes the storage and loads what an earlier run kept. */
    fun attach(store: DashReadingsStore) {
        synchronized(lock) {
            this.store = store
            // What the dash already said in this run (the store may load a moment after it connects) is newer than the file.
            store.load().forEach { (key, reading) -> if (key !in map) map[key] = reading }
            publish(System.currentTimeMillis())
        }
    }

    /** Test-only: no storage, so what a test saved does not follow it into the next. */
    internal fun testOnlyDetach() {
        synchronized(lock) { store = null }
    }

    /** Forgets everything, the stored copy too ("Esquecer a moto"). */
    fun clear() {
        synchronized(lock) {
            map.clear()
            state.value = emptyMap()
            settingsState.value = DashReport.Settings()
            // After any save already queued, so a value saved a moment ago does not come back.
            val target = store
            if (target != null) saver.execute { target.clear() }
            dirty.clear()
            unpublished = false
            // A new bike starts the pace of publishing and saving over.
            publishedAtMs = 0L
            savedAtMs = 0L
        }
    }

    /** [DashSession]'s hand-over: the value [value] of TLV ([type], [sub]), now; for the 0F group already decrypted. */
    override fun onValue(type: Int, sub: Int, value: ByteArray) = onValue(type, sub, value, System.currentTimeMillis())

    internal fun onValue(type: Int, sub: Int, value: ByteArray, nowMs: Long) {
        val key = DashReport.key(type, sub)
        // Only what the screen shows is kept; the dash says the `0C` group alone several times a second.
        if (!DashReport.isShown(key)) return
        val hex = value.joinToString("") { "%02X".format(it) }
        synchronized(lock) {
            val before = map[key]
            map[key] = DashReading(hex, nowMs, (before?.count ?: 0) + 1)
            unpublished = true
            if (before == null || before.hex != hex) dirty += key
            if (nowMs - publishedAtMs >= PUBLISH_EVERY_MS) publish(nowMs)
            if (dirty.isNotEmpty() && nowMs - savedAtMs >= SAVE_EVERY_MS) save(nowMs)
        }
    }

    /** The session ended: the screen and the storage get the latest. */
    override fun flush() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (unpublished) publish(now)
            if (dirty.isNotEmpty()) save(now)
        }
    }

    private fun publish(nowMs: Long) {
        state.value = HashMap(map)
        settingsState.value = DashReport.settings(map)
        publishedAtMs = nowMs
        unpublished = false
    }

    /** Hands what changed to the storage's thread; the caller (the dash's receive loop) does not wait for the Keystore. */
    private fun save(nowMs: Long) {
        val target = store
        if (target != null) {
            val changed = dirty.mapNotNull { key -> map[key]?.let { key to it } }.toMap()
            if (changed.isNotEmpty()) saver.execute { target.save(changed) }
        }
        savedAtMs = nowMs
        dirty.clear()
    }

    private const val PUBLISH_EVERY_MS = 1_000L
    private const val SAVE_EVERY_MS = 5_000L
}

/** The phone's storage of [DashReadings]: one preference per code, `hex|time|count`; [prefs] is the encrypted store. */
class DashReadingsStore(private val prefs: SharedPreferences) {
    fun load(): Map<String, DashReading> = prefs.all.mapNotNull { (key, value) ->
        val parts = (value as? String)?.split('|') ?: return@mapNotNull null
        val atMs = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        key to DashReading(parts[0], atMs, parts.getOrNull(2)?.toIntOrNull() ?: 1)
    }.toMap()

    fun save(readings: Map<String, DashReading>) {
        prefs.edit {
            readings.forEach { (key, r) -> putString(key, "${r.hex}|${r.atMs}|${r.count}") }
        }
    }

    fun clear() = prefs.edit { clear() }

    companion object {
        private const val FILE = "dash_readings"

        fun open(context: android.content.Context): DashReadingsStore =
            DashReadingsStore(com.reau.app.data.KeystoreBackedPrefs.open(context.applicationContext, FILE))
    }
}
