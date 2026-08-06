package dev.thedukerchip.scandy.camera

import com.google.mlkit.vision.barcode.common.Barcode
import java.io.Serializable

sealed class ScandyBarcode : Serializable {
    object Unknown : ScandyBarcode()
    class Text(val text: String) : ScandyBarcode()
    class Link(val title: String?, val url: String?) : ScandyBarcode()

//    val TYPE_UNKNOWN = 0
//    val TYPE_TEXT = 7
//    val TYPE_URL = 8
//
//    // @Todo Later
//    val TYPE_CALENDAR_EVENT = 11
//    val TYPE_CONTACT_INFO = 1
//    val TYPE_EMAIL = 2
//    val TYPE_PHONE = 4
//    val TYPE_WIFI = 9
//    val TYPE_ISBN = 3
//    val TYPE_PRODUCT = 5
//    val TYPE_SMS = 6
//    val TYPE_GEO = 10
//    val TYPE_DRIVER_LICENSE = 12
}

fun Barcode.toScandyBarcode(): ScandyBarcode {
    return when (valueType) {
        Barcode.TYPE_TEXT -> {
            val text = displayValue?.trim() ?: ""
            ScandyBarcode.Text(text)
        }

        Barcode.TYPE_URL -> {
            ScandyBarcode.Link(url!!.title, url!!.url)
        }

        else -> ScandyBarcode.Unknown
    }
}
