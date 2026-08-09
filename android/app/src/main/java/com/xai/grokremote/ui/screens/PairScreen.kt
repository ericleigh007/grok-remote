package com.xai.grokremote.ui.screens

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.xai.grokremote.ui.theme.Muted
import com.xai.grokremote.ui.theme.Panel
import com.xai.grokremote.ui.theme.TextPrimary
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PairScreen(onPaired: (baseUrl: String, token: String) -> Unit) {
    var base by remember { mutableStateOf("https://YOUR-MACHINE.YOUR-TAILNET.ts.net") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Scan the PC /pair QR, or paste base URL + token.") }
    val handled = remember { AtomicBoolean(false) }

    fun tryParsePairPayload(raw: String) {
        if (!handled.compareAndSet(false, true)) return
        try {
            // Full connect URL: https://host/?token=...
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                val u = URI(raw)
                val q = u.query?.split("&")?.associate {
                    val p = it.split("=", limit = 2)
                    p[0] to java.net.URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
                }.orEmpty()
                val t = q["token"] ?: q["t"] ?: q["server-key"]
                if (!t.isNullOrBlank()) {
                    val baseUrl = "${u.scheme}://${u.authority}"
                    onPaired(baseUrl, t)
                    return
                }
            }
            // grokremote://pair?base=...&token=...
            if (raw.startsWith("grokremote://")) {
                val u = URI(raw)
                val q = u.query?.split("&")?.associate {
                    val p = it.split("=", limit = 2)
                    p[0] to java.net.URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
                }.orEmpty()
                val b = q["base"]
                val t = q["token"]
                if (!b.isNullOrBlank() && !t.isNullOrBlank()) {
                    onPaired(b, t)
                    return
                }
            }
            status = "QR did not contain a pair URL"
            handled.set(false)
        } catch (e: Exception) {
            status = "Parse error: ${e.message}"
            handled.set(false)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Grok Remote", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Text(
            "On the PC open http://127.0.0.1:8787/pair and scan the QR. " +
                "Prefer the https://…ts.net URL for best results.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
        ) {
            QrScanner(onBarcode = { tryParsePairPayload(it) })
        }

        Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = base,
            onValueChange = { base = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token (fallback)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                if (base.isNotBlank() && token.isNotBlank()) onPaired(base.trim(), token.trim())
                else status = "Need base URL and token"
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.height(24.dp),
        )
    }
}

@Composable
private fun QrScanner(onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { imageProxy ->
                    val media = imageProxy.image
                    if (media != null) {
                        val image = InputImage.fromMediaImage(
                            media,
                            imageProxy.imageInfo.rotationDegrees,
                        )
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val raw = barcodes
                                    .firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                    ?.rawValue
                                if (!raw.isNullOrBlank()) onBarcode(raw)
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}
