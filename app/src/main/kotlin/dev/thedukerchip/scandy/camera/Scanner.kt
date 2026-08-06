package dev.thedukerchip.scandy.camera

import android.content.Context
import android.util.Size
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.asFlow
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class Scanner(private val context: Context) {

    private val _status = MutableStateFlow<ScannerStatus>(ScannerStatus.Off)
    val status: StateFlow<ScannerStatus> = _status.asStateFlow()

    private val _flash = MutableStateFlow(FlashStatus.Unavailable)
    val flash: StateFlow<FlashStatus> = _flash.asStateFlow()

    private var camera: Camera? = null

    @MainThread
    suspend fun scan(viewFinder: Preview.SurfaceProvider, owner: LifecycleOwner): ScandyBarcode {
        _status.value = ScannerStatus.Starting

        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (cause: Exception) {
            _status.value = ScannerStatus.Unavailable(cause)
            throw cause
        }

        val hdResolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val preview = Preview.Builder().build().apply { surfaceProvider = viewFinder }
        val frames = ImageAnalysis.Builder()
            .setResolutionSelector(hdResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val camera = try {
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, frames)
        } catch (cause: Exception) {
            _status.value = ScannerStatus.Unavailable(cause)
            throw cause
        }

        this.camera = camera

        return try {
            BarcodeReader().use { reader ->
                coroutineScope {
                    val watchingFlash = launch { trackFlash(camera) }
                    try {
                        _status.value = ScannerStatus.Scanning
                        frames.barcodes(reader).first()
                    } finally {
                        watchingFlash.cancel()
                    }
                }
            }
        } finally {
            provider.unbind(preview, frames)
            this.camera = null
            _flash.value = FlashStatus.Unavailable
            _status.value = ScannerStatus.Off
        }
    }

    private suspend fun trackFlash(camera: Camera) {
        if (!camera.cameraInfo.hasFlashUnit()) {
            _flash.value = FlashStatus.Unavailable
            return
        }

        camera.cameraInfo.torchState.asFlow().collect { _flash.value = it.toFlashStatus() }
    }

    @MainThread
    fun toggleFlash() {
        when (_flash.value) {
            FlashStatus.On -> camera?.cameraControl?.enableTorch(false)
            FlashStatus.Off -> camera?.cameraControl?.enableTorch(true)
            FlashStatus.Unavailable -> Unit
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun ImageAnalysis.barcodes(reader: BarcodeReader): Flow<ScandyBarcode> = callbackFlow {
    setAnalyzer(Dispatchers.IO.asExecutor()) { frame ->
        frame.use {
            val media = it.image ?: return@use
            val image = InputImage.fromMediaImage(media, it.imageInfo.rotationDegrees)
            val barcode = runBlocking { runCatching { reader.read(image) }.getOrNull() }
            if (barcode != null) trySend(barcode)
        }
    }
    awaitClose {
        clearAnalyzer()
    }
}