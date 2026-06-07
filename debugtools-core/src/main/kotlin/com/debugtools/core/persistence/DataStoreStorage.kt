package com.debugtools.core.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * A [SettingsStorage] implementation backed by Jetpack DataStore Preferences.
 *
 * **Threading contract**: Read operations use [runBlocking] on the calling thread.
 * This is intentional and safe ONLY because this class is always called from Presenter
 * code running on [Dispatchers.IO], never from the main thread. Calling from the main
 * thread will deadlock.
 *
 * Write operations also use [runBlocking] to guarantee write-then-read consistency on the
 * same IO thread. Call [close] when the owning module detaches (no-op in the current
 * implementation but kept for lifecycle symmetry with future async variants).
 *
 * DataStore requires exactly one instance per backing file. [getDataStore] ensures this
 * by caching instances keyed by name. Tests use unique names (via [System.nanoTime]) to
 * get fresh, isolated state without shared-instance conflicts.
 *
 * @param context Application context.
 * @param name    DataStore file name — must be unique per logical storage unit.
 */
class DataStoreStorage(
    private val context: Context,
    private val name: String = "debugtools_settings"
) : SettingsStorage {

    private val dataStore: DataStore<Preferences> = context.getDataStore(name)

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Synchronously reads the current [Preferences] snapshot.
     * Safe to call only from IO threads — see class-level threading contract.
     */
    private fun snapshot(): Preferences = runBlocking(Dispatchers.IO) {
        dataStore.data.first()
    }

    /**
     * Synchronously performs an edit by blocking the calling (IO) thread until
     * DataStore has persisted the change. This guarantees write-then-read consistency
     * within the same thread and is safe here because callers are always on IO threads.
     */
    private fun write(block: suspend (MutablePreferences) -> Unit) {
        runBlocking(Dispatchers.IO) { dataStore.edit { block(it) } }
    }

    // ------------------------------------------------------------------
    // SettingsStorage — String
    // ------------------------------------------------------------------

    override fun putString(key: String, value: String) =
        write { it[stringPreferencesKey(key)] = value }

    override fun getString(key: String, default: String): String =
        snapshot()[stringPreferencesKey(key)] ?: default

    // ------------------------------------------------------------------
    // SettingsStorage — StringSet
    // ------------------------------------------------------------------

    override fun putStringSet(key: String, value: Set<String>) =
        write { it[stringSetPreferencesKey(key)] = value }

    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        snapshot()[stringSetPreferencesKey(key)]?.toSet() ?: default

    // ------------------------------------------------------------------
    // SettingsStorage — Boolean
    // ------------------------------------------------------------------

    override fun putBoolean(key: String, value: Boolean) =
        write { it[booleanPreferencesKey(key)] = value }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        snapshot()[booleanPreferencesKey(key)] ?: default

    // ------------------------------------------------------------------
    // SettingsStorage — remove / clear
    // ------------------------------------------------------------------

    override fun remove(key: String) = write { prefs ->
        // Match by name across all key types (String, Boolean, StringSet, etc.)
        prefs.asMap().keys
            .filter { it.name == key }
            .forEach { prefs.remove(it) }
    }

    override fun clear() = write { it.clear() }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Lifecycle hook — call from the owning module's [onDetach]. Kept for API symmetry;
     * since all IO runs in [runBlocking], there is no scope to cancel here. A future
     * async variant (with fire-and-forget writes) should cancel its scope here.
     */
    fun close() { /* no-op: runBlocking writes need no cancellation */ }
}

// ---------------------------------------------------------------------------
// DataStore instance cache
//
// DataStore throws if more than one instance is created for the same file.
// This map guarantees one instance per name for the process lifetime.
// Tests use unique names to bypass the cache and get fresh state.
// ---------------------------------------------------------------------------

private val datastores = mutableMapOf<String, DataStore<Preferences>>()

private fun Context.getDataStore(name: String): DataStore<Preferences> =
    synchronized(datastores) {
        datastores.getOrPut(name) {
            PreferenceDataStoreFactory.create(
                produceFile = {
                    applicationContext.filesDir.resolve("datastore/$name.preferences_pb")
                }
            )
        }
    }
