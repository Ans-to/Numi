package com.calcvault.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.calcvault.core.Calculator
import com.calcvault.core.PinVerifier
import com.calcvault.core.SessionClock
import com.calcvault.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppState(
    val ready: Boolean = false, val fatal: Boolean = false, val configured: Boolean = false,
    val unlocked: Boolean = false, val concealed: Boolean = false, val busy: Boolean = false,
    val input: String = "", val result: String = "0", val pinStage: Int = 0, val changingPin: Boolean = false,
    val preferences: Preferences = Preferences(), val catalog: Catalog = Catalog(),
    val message: String? = null, val pendingImports: Int = 0, val viewingId: String? = null
)
class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    lateinit var vault: VaultRepository; private set
    lateinit var auth: AuthRepository; private set
    private val session = SessionClock()
    private var confirmation: CharArray? = null
    private var timeoutJob: Job? = null
    private var epoch = 0
    private var pending = emptyList<Uri>()
    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val secrets = SecretStore(application)
                    auth = AuthRepository(secrets)
                    check(auth.configured || !secrets.exists("catalog")) { "Authentication record is missing" }
                    vault = VaultRepository(application, secrets)
                }
                mutable.update { it.copy(ready = true, configured = auth.configured, preferences = auth.preferences) }
            } catch (_: Exception) { mutable.update { it.copy(ready = true, fatal = true, message = "Secure storage cannot be opened. A key or security record may be missing or damaged. Your encrypted files have not been erased. Do not clear app data; there is no recovery backdoor.") } }
        }
    }
    fun view(id: String?) { mutable.update { it.copy(viewingId = id) } }
    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    fun key(key: String) {
        if (state.value.busy || state.value.fatal) return
        if (key == "=") { equals(); return }
        mutable.update { s ->
            val input = when (key) {
                "AC" -> ""
                "⌫" -> s.input.dropLast(1)
                "±" -> if (s.input.startsWith('-')) s.input.drop(1) else "-${s.input}"
                else -> if (s.pinStage == 0 && s.input.isEmpty() && key in listOf("+", "−", "×", "÷", "%") && s.result.toBigDecimalOrNull() != null) s.result + key
                else if (s.input.length < (if (s.pinStage > 0) 12 else 256) && (s.pinStage == 0 || key.all { it.isDigit() })) s.input + key else s.input
            }
            s.copy(input = input, result = if (key == "AC") "0" else s.result)
        }
    }
    fun startPinChange() {
        if (state.value.configured && !state.value.unlocked) return
        confirmation?.fill('\u0000'); confirmation = null
        mutable.update { it.copy(pinStage = 1, changingPin = it.unlocked, input = "", result = "0") }
    }
    fun cancelPin() {
        confirmation?.fill('\u0000'); confirmation = null
        mutable.update { it.copy(pinStage = 0, changingPin = false, input = "") }
    }
    private fun equals() {
        val s = state.value
        if (s.pinStage > 0) { setupPin(); return }
        if (s.input.isBlank()) return
        val expression = s.input
        val pin = expression.toCharArray()
        val ticket = epoch
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                val attempt = if (s.configured && PinVerifier.isCandidate(pin)) withContext(Dispatchers.IO) { auth.attempt(pin) } else UnlockResult.Calculate
                if (ticket != epoch || state.value.concealed) return@launch
                when (attempt) {
                    UnlockResult.Open -> openVault(ticket)
                    is UnlockResult.Delayed -> { mutable.update { it.copy(input = "", result = "0") }; message("Please wait ${attempt.seconds} seconds before another PIN-length entry. Other calculations are available.") }
                    UnlockResult.Calculate -> {
                        val result = runCatching { Calculator.evaluate(expression) }.getOrElse { "Invalid calculation" }
                        mutable.update { it.copy(input = "", result = result) }
                    }
                }
            } catch (_: Exception) { message("Authentication or storage could not be verified. Nothing was erased.") }
            finally { pin.fill('\u0000'); mutable.update { it.copy(busy = false) } }
        }
    }
    private fun setupPin() {
        val s = state.value
        val pin = s.input.toCharArray()
        if (!PinVerifier.isCandidate(pin)) { pin.fill('\u0000'); message("Choose 6–12 digits, including any leading zeros."); return }
        if (s.pinStage == 1) {
            confirmation?.fill('\u0000'); confirmation = pin
            mutable.update { it.copy(pinStage = 2, input = "") }; return
        }
        if (confirmation?.contentEquals(pin) != true) {
            pin.fill('\u0000'); confirmation?.fill('\u0000'); confirmation = null
            mutable.update { it.copy(pinStage = 1, input = "") }; message("PINs did not match. Please start again."); return
        }
        confirmation?.fill('\u0000'); confirmation = null
        val ticket = epoch
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, input = "") }
            try {
                withContext(Dispatchers.IO) { if (s.changingPin) auth.changePin(pin) else auth.setup(pin) }
                mutable.update { it.copy(configured = true, pinStage = 0, changingPin = false) }
                if (ticket == epoch) message(if (s.changingPin) "PIN changed." else "PIN saved. Enter it on the keypad and press = to unlock. There is no PIN recovery.")
            } catch (_: Exception) { message("PIN could not be saved. Please try again."); cancelPin() }
            finally { pin.fill('\u0000'); mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun openVault(ticket: Int) {
        val catalog = withContext(Dispatchers.IO) { vault.unlock() }
        if (ticket != epoch || state.value.concealed) { vault.lock(); return }
        session.unlock()
        mutable.update { it.copy(unlocked = true, input = "", result = "0", catalog = catalog, preferences = auth.preferences) }
    }
    fun biometricUnlocked() {
        val ticket = epoch
        if (state.value.concealed || state.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try { withContext(Dispatchers.IO) { auth.resetAttempts() }; openVault(ticket) }
            catch (_: Exception) { message("The vault could not be opened. Use your PIN; encrypted files have been preserved.") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun lock() {
        epoch++; timeoutJob?.cancel(); session.lock(); cancelPin()
        if (::vault.isInitialized) vault.lock()
        mutable.update { it.copy(unlocked = false, input = "", result = "0", catalog = Catalog(), message = null, viewingId = null) }
    }
    fun conceal() { mutable.update { it.copy(concealed = true) } }
    fun background() {
        val timeout = state.value.preferences.lockSeconds * 1000L
        session.background(SystemClock.elapsedRealtime(), timeout)
        if (timeout == 0L) lock()
        else {
            timeoutJob?.cancel()
            timeoutJob = viewModelScope.launch { delay(timeout); lock() }
        }
    }
    fun foreground() {
        timeoutJob?.cancel()
        session.foreground(SystemClock.elapsedRealtime(), state.value.preferences.lockSeconds * 1000L)
        if (state.value.unlocked && !session.unlocked) lock()
        mutable.update { it.copy(concealed = false) }
    }
    fun receiveImports(uris: List<Uri>) {
        pending = uris.take(100)
        mutable.update { it.copy(pendingImports = pending.size) }
        if (uris.size > 100) message("Select up to 100 files at a time.")
    }
    fun discardImports() { pending = emptyList(); mutable.update { it.copy(pendingImports = 0) } }
    fun importPending(folder: String?) {
        if (!state.value.unlocked || state.value.busy) return
        val selected = pending; discardImports()
        operation {
            var imported = 0; var duplicates = 0; var failed = 0
            selected.forEach { uri ->
                if (vault.unlocked) try { if (vault.import(uri, folder)) imported++ else duplicates++ } catch (_: Exception) { failed++ }
                else failed++
            }
            "$imported imported · $duplicates duplicates skipped · $failed not imported. Originals are unchanged." +
                if (failed > 0) " Check available storage and source access, then select those files again." else ""
        }
    }
    fun rename(id: String, name: String) = operation { vault.rename(id, name); "Renamed." }
    fun folder(name: String) = operation { vault.createFolder(name); "Folder created." }
    fun move(ids: Set<String>, folder: String?) = operation { vault.move(ids, folder); "Items moved." }
    fun delete(ids: Set<String>) = operation { vault.delete(ids); "Permanently deleted from this vault. Originals are unchanged." }
    fun preferences(p: Preferences) = operation { auth.settings(p); "Settings saved." }
    fun biometricSaved(iv: ByteArray?, cipher: ByteArray?) = operation { auth.setBiometric(iv, cipher); if (cipher == null) "Biometrics disabled." else "Biometrics enabled. Your PIN remains available." }
    private fun operation(block: () -> String) {
        if (!state.value.unlocked || state.value.busy) return
        val ticket = epoch
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                val result = withContext(Dispatchers.IO) { block() }
                if (ticket == epoch && state.value.unlocked) mutable.update { it.copy(catalog = vault.snapshot(), preferences = auth.preferences, message = result) }
            } catch (_: Exception) {
                if (ticket == epoch) message("Operation could not finish. Check the name, available storage, and file access. Encrypted data was not reset; try unlocking again if needed.")
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() { confirmation?.fill('\u0000'); if (::vault.isInitialized) vault.lock() }
}
