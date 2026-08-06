package dev.thedukerchip.scandy.camera

import androidx.camera.core.TorchState

enum class FlashStatus { On, Off, Unavailable }

internal fun Int?.toFlashStatus() = when (this) {
    TorchState.ON -> FlashStatus.On
    TorchState.OFF -> FlashStatus.Off
    else -> FlashStatus.Unavailable
}