package com.calcvault.ui

import android.app.Application
import android.net.Uri
import android.content.Intent
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
    val message: String? = null, val pendingImports: Int = 0, val viewingId: String? = null, val importProgress: String? = null, val removalNames: List<String> = emptyList()
)
class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    lateinit var vault: VaultRepository; private set
    lateinit var auth: AuthRepository; private set
    private val session = SessionClock()
    private var confirmation: CharArray? = null
    private var timeoutJob: Job? = null
    @Volatile private var epoch = 0
    private var backgrounded = false
    private var pending = emptyList<Uri>()
    private var importFolder: String? = null
    private var autoImport = false
    private var importReport: String? = null
    private val heldReadGrants = mutableMapOf<Uri, Int>()
    private var moveOriginals = false
    private var removalCandidates = emptyList<SourceRemovalCandidate>()
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
    fun message(text: String?) { if (text == null) importReport = null; mutable.update { it.copy(message = text) } }
    fun key(key: String) {
        if (state.value.busy || state.value.fatal) return
        if (key == "=") { equals(); return }
        mutable.update { s ->
            val inputLimit = if (s.pinStage > 0) 12 else 256
            val input = when (key) {
                "AC" -> ""
                "⌫" -> s.input.dropLast(1)
                "±" -> if (s.input.startsWith('-')) s.input.drop(1) else "-${s.input}"
                else -> {
                    if (s.pinStage == 0 && s.input.isEmpty() && key in listOf("+", "−", "×", "÷", "%") && s.result.toBigDecimalOrNull() != null) {
                        s.result + key
                    } else if (s.input.length < inputLimit && (s.pinStage == 0 || key.all { it.isDigit() })) {
                        s.input + key
                    } else {
                        s.input
                    }
                }
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
                    // Throttling only prevents unlock checks, never ordinary arithmetic.
                    // Do not announce vault/PIN information on the calculator screen.
                    is UnlockResult.Delayed, UnlockResult.Calculate -> {
                        val result = runCatching { Calculator.evaluate(expression) }.getOrElse { "Invalid calculation" }
                        mutable.update { it.copy(input = "", result = result) }
                    }
                }
            } catch (_: Exception) { message("Authentication or storage could not be verified. Nothing was erased.") }
            finally { pin.fill('\u0000'); mutable.update { it.copy(busy = false) }; tryStartPendingImports() }
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
            finally { pin.fill('\u0000'); mutable.update { it.copy(busy = false) }; tryStartPendingImports() }
        }
    }
    private suspend fun openVault(ticket: Int) {
        val catalog = withContext(Dispatchers.IO) { vault.unlock() }
        if (ticket != epoch || state.value.concealed) { vault.lock(); return }
        session.unlock()
        mutable.update { it.copy(unlocked = true, input = "", result = "0", catalog = catalog, preferences = auth.preferences, removalNames = removalCandidates.map { it.name }, message = listOfNotNull(vault.recoveryWarning, importReport).joinToString("\n\n").ifBlank { null }) }
    }
    fun biometricUnlocked() {
        val ticket = epoch
        if (state.value.concealed || state.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try { withContext(Dispatchers.IO) { auth.resetAttempts() }; openVault(ticket) }
            catch (_: Exception) { message("The vault could not be opened. Use your PIN; encrypted files have been preserved.") }
            finally { mutable.update { it.copy(busy = false) }; tryStartPendingImports() }
        }
    }
    fun lock() {
        epoch++; timeoutJob?.cancel(); session.lock(); cancelPin()
        if (::vault.isInitialized) vault.lock()
        mutable.update { it.copy(unlocked = false, input = "", result = "0", catalog = Catalog(), message = null, viewingId = null, importProgress = null, removalNames = emptyList()) }
    }
    fun conceal() { mutable.update { it.copy(concealed = true) } }
    fun background() {
        if (backgrounded) return
        backgrounded = true
        val timeout = state.value.preferences.lockSeconds * 1000L
        session.background(SystemClock.elapsedRealtime(), timeout)
        if (timeout == 0L) lock()
        else {
            timeoutJob?.cancel()
            timeoutJob = viewModelScope.launch { delay(timeout); lock() }
        }
    }
    fun foreground() {
        backgrounded = false
        timeoutJob?.cancel()
        session.foreground(SystemClock.elapsedRealtime(), state.value.preferences.lockSeconds * 1000L)
        if (state.value.unlocked && !session.unlocked) lock()
        mutable.update { it.copy(concealed = false) }
        tryStartPendingImports()
    }
    /** Called before launching the system picker; selection is explicit import consent. */
    fun prepareImport(folder: String?, moveOriginals: Boolean = false): Boolean {
        if (!state.value.unlocked || state.value.busy) return false
        if (removalCandidates.isNotEmpty()) {
            message("Review the waiting originals or choose Keep originals before starting another import.")
            return false
        }
        discardImports()
        this.moveOriginals = moveOriginals
        importFolder = folder
        // A translucent photo picker may pause us without ever calling onStop.
        // Start the configured lock timer when handing control to any picker.
        background()
        return true
    }
    fun receiveImports(uris: List<Uri>) {
        // This callback is Activity-owned and survives the calculator/vault UI switching.
        discardImports()
        pending = uris.distinct().take(100)
        pending.forEach { uri ->
            if (uri.scheme == "content") {
                val resolver = getApplication<Application>().contentResolver
                val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val wanted = if (moveOriginals) read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else read
                val granted = runCatching { resolver.takePersistableUriPermission(uri, wanted); wanted }
                    .getOrElse { runCatching { resolver.takePersistableUriPermission(uri, read); read }.getOrDefault(0) }
                if (granted != 0) heldReadGrants[uri] = granted
            }
        }
        autoImport = pending.isNotEmpty()
        mutable.update { it.copy(pendingImports = pending.size) }
        if (uris.size > 100) importReport = "Only the first 100 selected files will be imported. Select the rest in another batch."
        tryStartPendingImports()
    }
    private fun releaseReadGrant(uri: Uri) {
        val flags = heldReadGrants.remove(uri) ?: return
        runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(uri, flags) }
    }
    fun discardImports() {
        pending.forEach(::releaseReadGrant)
        pending = emptyList(); autoImport = false; importReport = null
        mutable.update { it.copy(pendingImports = 0) }
    }
    private fun tryStartPendingImports() {
        val s = state.value
        if (autoImport && s.ready && s.unlocked && !s.concealed && !s.busy && pending.isNotEmpty()) importPending()
    }
    fun importPending() {
        if (!state.value.unlocked || state.value.concealed || state.value.busy || pending.isEmpty()) return
        val selected = pending
        val folder = importFolder
        val moving = moveOriginals
        val ticket = epoch
        pending = emptyList(); autoImport = false
        mutable.update { it.copy(busy = true, pendingImports = 0, message = null, importProgress = "Preparing import…") }
        viewModelScope.launch {
            var imported = 0; var duplicates = 0
            val failed = mutableListOf<Uri>()
            val reasons = mutableListOf<String>()
            try {
                selected.forEachIndexed { index, uri ->
                    if (ticket == epoch && vault.unlocked) {
                        mutable.update { it.copy(importProgress = "Encrypting & verifying ${index + 1} of ${selected.size}…") }
                        try {
                            val copy = withContext(Dispatchers.IO) { vault.importWithReceipt(uri, folder) }
                            if (copy.added) imported++ else duplicates++
                            val candidate = if (moving) withContext(Dispatchers.IO) {
                                runCatching { SourceRemoval(getApplication(), vault).prepare(uri, copy) { vault.unlocked && ticket == epoch } }.getOrNull()
                            } else null
                            if (candidate != null) removalCandidates = removalCandidates + candidate
                            else {
                                releaseReadGrant(uri)
                                if (moving) reasons += "File ${index + 1}: removal is not available. The provider may be read-only or the vault copy could not be reverified. Keep the original until you check the vault copy, then remove it manually in Gallery or Files."
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            failed += uri
                            reasons += "File ${index + 1}: " + (if (ticket != epoch) ImportProblem.INTERRUPTED else (e as? VaultImportException)?.problem ?: ImportProblem.WRITE).help
                        }
                    } else { failed += uri; reasons += "File ${index + 1}: ${ImportProblem.INTERRUPTED.help}" }
                }
                pending = failed.toList() // Failed selections stay available for an explicit retry.
                importReport = "$imported imported · $duplicates already in vault · ${failed.size} not imported. Originals are unchanged." +
                    if (reasons.isEmpty()) "" else "\n\n" + reasons.take(3).joinToString("\n\n") + if (reasons.size > 3) "\n\n${reasons.size - 3} more files could not be imported." else ""
                if (removalCandidates.isNotEmpty()) importReport += "\n\n${removalCandidates.size} verified copies are ready. Use Review originals for a separate removal confirmation."
                mutable.update { it.copy(pendingImports = pending.size, removalNames = if (it.unlocked) removalCandidates.map { c -> c.name } else emptyList()) }
                if (ticket == epoch && state.value.unlocked) mutable.update { it.copy(catalog = vault.snapshot(), message = importReport) }
            } finally { mutable.update { it.copy(busy = false, importProgress = null) } }
        }
    }
    fun keepOriginals() {
        if (!state.value.unlocked || state.value.busy) return
        removalCandidates.forEach { releaseReadGrant(it.uri) }
        removalCandidates = emptyList()
        mutable.update { it.copy(removalNames = emptyList()) }
        message("Originals kept. The encrypted vault copies remain available.")
    }
    fun removeOriginalsConfirmed() {
        val s = state.value
        if (!s.unlocked || s.concealed || s.busy || removalCandidates.isEmpty()) return
        val selected = removalCandidates
        val ticket = epoch
        mutable.update { it.copy(busy = true, message = null, importProgress = "Rechecking copies before removal…") }
        viewModelScope.launch {
            val outcomes = mutableListOf<Pair<SourceRemovalCandidate, SourceRemovalResult>>()
            try {
                val remover = SourceRemoval(getApplication(), vault)
                selected.forEachIndexed { index, candidate ->
                    mutable.update { it.copy(importProgress = "Rechecking original ${index + 1} of ${selected.size}…") }
                    val outcome = withContext(Dispatchers.IO) {
                        remover.removeConfirmed(candidate) { vault.unlocked && ticket == epoch }
                    }
                    outcomes += candidate to outcome
                    // Do not silently retry requests whose provider result is ambiguous.
                    if (outcome != SourceRemovalResult.INTERRUPTED) {
                        removalCandidates = removalCandidates.filterNot { it.uri == candidate.uri }
                        releaseReadGrant(candidate.uri)
                    }
                }
                val removed = outcomes.count { it.second == SourceRemovalResult.REMOVED }
                importReport = "$removed originals removed by the provider; ${selected.size - removed} not confirmed removed. Vault copies retained. Gallery refresh, trash and cloud copies are controlled by their providers." +
                    outcomes.filter { it.second != SourceRemovalResult.REMOVED }.take(3).joinToString("", prefix = "\n\n") { "${it.first.name}: ${it.second.help}\n" }
                if (state.value.unlocked) mutable.update { it.copy(message = importReport) }
            } finally {
                mutable.update { it.copy(busy = false, importProgress = null, removalNames = if (it.unlocked) removalCandidates.map { c -> c.name } else emptyList()) }
            }
        }
    }
    fun rename(id: String, name: String) = operation { vault.rename(id, name); "Renamed." }
    fun folder(name: String) = operation { vault.createFolder(name); "Folder created." }
    fun move(ids: Set<String>, folder: String?) = operation { vault.move(ids, folder); "Items moved." }
    fun delete(ids: Set<String>) = operation {
        val remaining = vault.delete(ids)
        if (remaining == 0) "Permanently deleted from this vault. Originals are unchanged."
        else "Item keys were removed, but $remaining encrypted files could not be reclaimed. These vault copies are no longer accessible. Originals are unchanged."
    }
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
                if (ticket == epoch && state.value.unlocked) {
                    // A catalog commit can succeed before later cleanup fails. Never show stale valid items.
                    mutable.update { it.copy(catalog = vault.snapshot(), preferences = auth.preferences) }
                    message("Operation could not finish. Check the name, available storage, and file access. The list reflects committed changes; try unlocking again if needed.")
                }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() { confirmation?.fill('\u0000'); if (::vault.isInitialized) vault.lock(); heldReadGrants.keys.toList().forEach(::releaseReadGrant) }
}
