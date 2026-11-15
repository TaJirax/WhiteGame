package com.whitegame.app.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whitegame.app.connection.EnrollmentKey
import com.whitegame.app.connection.HostedProvisioning
import com.whitegame.app.connection.TunnelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HostedSetupViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val store: TunnelStore
) : ViewModel() {
    private val prefs = context.getSharedPreferences("hosted_setup", Context.MODE_PRIVATE)
    var serverUrl by mutableStateOf(prefs.getString("last_server", "").orEmpty())
        private set
    // The enrollment credential is deliberately not saved to disk or saved-instance state.
    var token by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set

    fun updateServer(value: String) { if (!busy) { serverUrl = value; message = "" } }
    fun updateToken(value: String) { if (!busy) token = value }

    fun enroll() {
        if (busy) return
        val server = runCatching { HostedProvisioning.normalizeServer(serverUrl) }.getOrElse { message = it.message.orEmpty(); return }
        if (token.isBlank()) { message = "Enter your server's enrollment token"; return }
        val credential = token
        busy = true
        message = "Registering this phone with your WireGuard server…"
        viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    val key = EnrollmentKey.loadOrCreate(
                        read = { prefs.getString("key:$server", null) },
                        write = { prefs.edit().putString("key:$server", it).putString("last_server", server).commit() }
                    )
                    HostedProvisioning.register(server, credential, key)
                }
                val id = prefs.getString("profile:$server", null)?.takeIf { store.find(it) != null }
                val saved = store.save("WireGuard ${java.net.URI(server).host}", profile.confText, id)
                store.select(saved.id)
                prefs.edit().putString("profile:$server", saved.id).putString("last_server", server).apply()
                serverUrl = server
                token = ""
                message = "Server profile saved and selected. Connect it from the Connect tab."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Enrollment failed. Retry to reuse this phone's existing device key." }
            finally { busy = false }
        }
    }
}
