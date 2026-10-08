package app.stillroom.data

import android.graphics.Bitmap
import app.stillroom.domain.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** The same bundled detector and target filtering are used by CameraX and fixture instrumentation. */
class BarcodeDecoder : AutoCloseable {
    val detector = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
        Barcode.FORMAT_EAN_13,Barcode.FORMAT_EAN_8,Barcode.FORMAT_UPC_A,Barcode.FORMAT_UPC_E,
        Barcode.FORMAT_QR_CODE,Barcode.FORMAT_CODE_128,Barcode.FORMAT_CODE_39,Barcode.FORMAT_ITF,
        Barcode.FORMAT_DATA_MATRIX).build())
    fun scan(bitmap:Bitmap)=detector.process(InputImage.fromBitmap(bitmap,0))
    fun inTarget(barcodes:List<Barcode>,target:ScanRegion):List<ScanCode> = barcodes.mapNotNull { barcode ->
        val bounds=barcode.boundingBox ?: return@mapNotNull null
        if(!target.containsCenter(ScanRegion(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat()))) return@mapNotNull null
        val raw=barcode.rawValue ?: return@mapNotNull null
        val format=when(barcode.format) {
            Barcode.FORMAT_EAN_13 -> ScanFormat.Ean13
            Barcode.FORMAT_EAN_8 -> ScanFormat.Ean8
            Barcode.FORMAT_UPC_A -> ScanFormat.UpcA
            Barcode.FORMAT_UPC_E -> ScanFormat.UpcE
            Barcode.FORMAT_QR_CODE -> ScanFormat.Qr
            else -> ScanFormat.Other
        }
        runCatching { ScanCode(raw,format).validated() }.getOrNull()
    }.distinctBy { it.raw }
    override fun close()=detector.close()
}
