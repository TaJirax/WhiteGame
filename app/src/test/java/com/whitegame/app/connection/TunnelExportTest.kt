package com.whitegame.app.connection

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelExportTest {
    @Test
    fun qrRecoversTheWholeConfig() {
        val config = """
            [Interface]
            # دفتر
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.8.0.2/32

            [Peer]
            PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
            AllowedIPs = 10.8.0.0/24
            Endpoint = vpn.example.com:51820
        """.trimIndent() + "\n"
        val matrix = encodeTunnelQr(config)
        val scale = 4
        val side = matrix.width * scale
        val pixels = IntArray(side * side) { index ->
            if (matrix[(index % side) / scale, (index / side) / scale]) {
                0xFF000000.toInt()
            } else {
                0xFFFFFFFF.toInt()
            }
        }
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        val decoded = QRCodeReader().decode(image, mapOf(DecodeHintType.PURE_BARCODE to true))
        assertEquals(config, decoded.text)
    }

    @Test
    fun exportNameFitsWireGuardInterfaceNameRules() {
        assertEquals("Office-VPN-Back.conf", tunnelConfigFileName("../Office VPN Backup"))
        assertEquals("whitegame.conf", tunnelConfigFileName("دفتر"))
    }
}
