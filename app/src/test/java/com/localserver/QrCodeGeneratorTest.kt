package com.localserver

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.localserver.core.QrCodeGenerator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeGeneratorTest {

    @Test
    fun testEmptyContentReturnsNull() {
        assertNull(QrCodeGenerator.generateQrBitmap(""))
        assertNull(QrCodeGenerator.generateQrBitmap("   "))
    }

    @Test
    fun testQrCodeWriterEncodesValidUrl() {
        val url = "http://192.168.1.50:8080"
        val bitMatrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 200, 200)
        assertNotNull(bitMatrix)
        assert(bitMatrix.width == 200)
        assert(bitMatrix.height == 200)
    }
}
