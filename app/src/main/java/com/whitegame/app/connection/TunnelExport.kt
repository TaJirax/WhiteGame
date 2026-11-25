package com.whitegame.app.connection

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

internal fun tunnelConfigFileName(tunnelName: String): String {
    val interfaceName = tunnelName
        .replace(Regex("[^a-zA-Z0-9_=+.\\-]+"), "-")
        .trim('.', '-')
        .take(15)
        .ifEmpty { "whitegame" }
    return "$interfaceName.conf"
}

internal fun encodeTunnelQr(config: String): BitMatrix = QRCodeWriter().encode(
    config, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name())
)
