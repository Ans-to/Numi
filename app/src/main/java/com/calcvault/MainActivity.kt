package com.calcvault

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.calcvault.data.BiometricGate
import com.calcvault.ui.CalcVaultApp
import com.calcvault.ui.VaultViewModel

class MainActivity : FragmentActivity() {
    private val model: VaultViewModel by viewModels()
    // Activity-owned registrations remain present when Compose conceals/removes the vault.
    // Keep registration order stable so Android can redeliver results after recreation.
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { model.receiveImports(it) }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.receiveImports(it) }
    private val movePicker = registerForActivityResult(object : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }) { model.receiveImports(it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val biometric = BiometricGate(this)
        setContent {
            CalcVaultApp(model, biometric,
                onPickMedia = { folder ->
                    if (model.prepareImport(folder)) runCatching {
                        mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }.onFailure { model.foreground(); model.message("No system photo picker is available. Try Documents & other files instead.") }
                },
                onPickMove = { folder ->
                    if (model.prepareImport(folder, moveOriginals = true)) runCatching { movePicker.launch(arrayOf("*/*")) }
                        .onFailure { model.foreground(); model.message("No system file picker is available. Nothing was removed.") }
                },
                onPickFiles = { folder ->
                    if (model.prepareImport(folder)) runCatching { filePicker.launch(arrayOf("*/*")) }
                        .onFailure { model.foreground(); model.message("No system file picker is available on this device.") }
                })
        }
    }
    override fun onPause() { model.conceal(); super.onPause() }
    override fun onStop() { if (!isChangingConfigurations) model.background(); super.onStop() }
    override fun onResume() { super.onResume(); model.foreground() }
}
