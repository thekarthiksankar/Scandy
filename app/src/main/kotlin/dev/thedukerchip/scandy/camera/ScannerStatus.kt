package dev.thedukerchip.scandy.camera

sealed class ScannerStatus {
    data object Off : ScannerStatus()
    data object Starting : ScannerStatus()
    data object Scanning : ScannerStatus()
    data class Unavailable(val reason: Throwable) : ScannerStatus()
}