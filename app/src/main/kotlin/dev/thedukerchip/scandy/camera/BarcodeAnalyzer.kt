package dev.thedukerchip.scandy.camera

import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage


typealias OnBarcodeDetected = (ScandyBarcode) -> Unit

@ExperimentalGetImage
class BarcodeAnalyzer(private val onBarcodeDetected: OnBarcodeDetected) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image

        mediaImage?.let {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            val scanner = BarcodeScanning.getClient()
            scanner.process(image).addOnSuccessListener {
                if (it.isNotEmpty()) {
                    it.forEach { barcode ->
                        val scandy = barcode.toScandyBarcode()
                        if (scandy !is ScandyBarcode.Unknown) {
                            onBarcodeDetected(scandy)
                        }
                    }
                }
                imageProxy.close()
            }.addOnFailureListener { exception ->
                Log.e("Barcode", "Unable to parse the barcode", exception)
                imageProxy.close()
            }
        }
    }
}