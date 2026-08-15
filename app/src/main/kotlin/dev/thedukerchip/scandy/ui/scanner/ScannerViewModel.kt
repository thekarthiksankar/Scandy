package dev.thedukerchip.scandy.ui.scanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.thedukerchip.scandy.camera.FlashStatus
import dev.thedukerchip.scandy.camera.ScannerStatus
import dev.thedukerchip.scandy.camera.Scanner
import kotlinx.coroutines.flow.StateFlow

class ScannerViewModel(app: Application) : AndroidViewModel(app) {
    val scanner = Scanner(app)
    val flash: StateFlow<FlashStatus> = scanner.flash
    val status: StateFlow<ScannerStatus> = scanner.status
    fun toggleFlash() = scanner.toggleFlash()
}
