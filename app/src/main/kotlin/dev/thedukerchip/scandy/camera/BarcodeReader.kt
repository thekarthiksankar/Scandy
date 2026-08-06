package dev.thedukerchip.scandy.camera

import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import java.io.Closeable

class BarcodeReader : Closeable {

    private val client: BarcodeScanner = BarcodeScanning.getClient()

    suspend fun read(image: InputImage): ScandyBarcode? =
        client.process(image).await()
            .asSequence()
            .map(Barcode::toScandyBarcode)
            .firstOrNull { it !is ScandyBarcode.Unknown }

    override fun close() {
        client.close()
    }
}
