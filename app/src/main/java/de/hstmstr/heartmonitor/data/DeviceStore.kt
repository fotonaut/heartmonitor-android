package de.hstmstr.heartmonitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The heart-rate strap the user last connected to, remembered across launches. */
data class RememberedDevice(val address: String, val name: String?)

private val Context.deviceDataStore: DataStore<Preferences> by preferencesDataStore(name = "devices")

/**
 * Persists the last successfully connected device via Preferences DataStore, so
 * the app can silently reconnect on the next launch and pre-select it in the
 * picker. Only the MAC address and last-known name are stored.
 */
class DeviceStore(context: Context) {

    private val dataStore = context.applicationContext.deviceDataStore

    val lastDevice: Flow<RememberedDevice?> = dataStore.data.map { prefs ->
        val address = prefs[KEY_ADDRESS] ?: return@map null
        RememberedDevice(address = address, name = prefs[KEY_NAME])
    }

    suspend fun remember(address: String, name: String?) {
        dataStore.edit { prefs ->
            prefs[KEY_ADDRESS] = address
            if (name.isNullOrBlank()) prefs.remove(KEY_NAME) else prefs[KEY_NAME] = name
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_ADDRESS)
            prefs.remove(KEY_NAME)
        }
    }

    private companion object {
        val KEY_ADDRESS = stringPreferencesKey("last_device_address")
        val KEY_NAME = stringPreferencesKey("last_device_name")
    }
}
