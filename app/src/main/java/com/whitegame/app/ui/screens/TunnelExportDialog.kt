package com.whitegame.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.WriterException
import com.whitegame.app.connection.SavedTunnel
import com.whitegame.app.connection.encodeTunnelQr
import com.whitegame.app.ui.components.VSpace
import com.whitegame.app.ui.theme.Panel
import com.whitegame.app.ui.theme.Space
import com.whitegame.app.ui.theme.Trace
import com.whitegame.app.ui.theme.Trace2
import com.whitegame.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TunnelExportDialog(
    tunnel: SavedTunnel,
    onSaveFile: () -> Unit,
    onDismiss: () -> Unit
) {
    val qr by produceState<Result<ImageBitmap>?>(null, tunnel.confText) {
        value = withContext(Dispatchers.Default) {
            try {
                val modules = encodeTunnelQr(tunnel.confText)
                val pixels = IntArray(modules.width * modules.height) { index ->
                    if (modules[index % modules.width, index / modules.width]) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                }
                Result.success(
                    Bitmap.createBitmap(
                        pixels, modules.width, modules.height, Bitmap.Config.ARGB_8888
                    ).asImageBitmap()
                )
            } catch (e: WriterException) {
                Result.failure(e)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        containerColor = Panel,
        titleContentColor = Trace,
        textContentColor = Trace2,
        title = { Text("Export tunnel config", style = Type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The QR code and .conf file both contain this tunnel’s private key.",
                    style = Type.body
                )
                VSpace(Space.md)
                val result = qr
                val image = result?.getOrNull()
                when {
                    image != null -> Image(
                        bitmap = image,
                        contentDescription = "Tunnel configuration QR code",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        filterQuality = FilterQuality.None
                    )
                    result == null -> Text("Generating QR code…", style = Type.body)
                    else -> Text("Config is too large for a QR code. Save the .conf file instead.", style = Type.body)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSaveFile) { Text("Save .conf", color = Trace, style = Type.button) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Trace2, style = Type.button) }
        }
    )
}
