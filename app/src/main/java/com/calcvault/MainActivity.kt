package com.calcvault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.calcvault.data.BiometricGate
import com.calcvault.ui.CalcVaultApp
import com.calcvault.ui.VaultViewModel

class MainActivity : FragmentActivity() {
    private val model: VaultViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val biometric = BiometricGate(this)
        setContent { CalcVaultApp(model, biometric) }
    }
    override fun onPause() { model.conceal(); super.onPause() }
    override fun onStop() { if (!isChangingConfigurations) model.background(); super.onStop() }
    override fun onResume() { super.onResume(); model.foreground() }
}
