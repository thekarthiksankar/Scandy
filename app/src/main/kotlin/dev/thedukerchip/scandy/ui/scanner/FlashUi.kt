package dev.thedukerchip.scandy.ui.scanner

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import dev.thedukerchip.scandy.camera.FlashStatus
import scandy.R

@ColorRes
fun FlashStatus.bgTint() = when (this) {
    FlashStatus.On -> R.color.flash_active
    FlashStatus.Off -> R.color.flash_inactive
    FlashStatus.Unavailable -> R.color.flash_inactive
}

@DrawableRes
fun FlashStatus.icon() = when (this) {
    FlashStatus.On -> R.drawable.ic_flash_active
    FlashStatus.Off -> R.drawable.ic_flash_inactive
    FlashStatus.Unavailable -> R.drawable.ic_flash_inactive
}
