package com.lcdcode.shardquorum.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lcdcode.shardquorum.R
import com.lcdcode.shardquorum.qr.QrFrameAnalyzer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

private enum class InputMethod { WORDS, SCAN, FILE }

// Picked shard files are tiny (a few hundred bytes of words); QR images are at
// most a few MB. These caps bound how much of a hostile or mistaken pick we read
// into memory, since a content URI may report no size up front.
private const val MAX_TEXT_BYTES = 1 shl 20 // 1 MiB
private const val MAX_IMAGE_BYTES = 16 shl 20 // 16 MiB

/** Reads at most [cap] bytes; returns null if the stream exceeds it. */
private fun readCapped(input: InputStream, cap: Int): ByteArray? {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        total += read
        if (total > cap) return null
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

/**
 * Shared shard-input UI used by both the recover flow and the create-flow
 * verify step. It is ViewModel-agnostic: every method funnels to two callbacks.
 *
 * @param onText typed words, a camera-decoded QR, or the contents of a text
 *   file. Returns true if accepted (the word field clears on success).
 * @param onImageBytes raw bytes of a picked image file; the host decodes it
 *   (it owns the [com.lcdcode.shardquorum.qr.QrDecoder]).
 */
@Composable
fun ShardInputPanel(
    onText: (String) -> Boolean,
    onImageBytes: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    var method by rememberSaveable { mutableStateOf(InputMethod.WORDS) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MethodSelector(method, onSelect = { method = it })
        when (method) {
            InputMethod.WORDS -> WordEntryPanel(onText)
            InputMethod.SCAN -> CameraScannerPanel(onText)
            InputMethod.FILE -> FileImportPanel(onText, onImageBytes)
        }
    }
}

@Composable
private fun MethodSelector(selected: InputMethod, onSelect: (InputMethod) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == InputMethod.WORDS,
            onClick = { onSelect(InputMethod.WORDS) },
            label = { Text(stringResource(R.string.recover_method_words)) },
        )
        FilterChip(
            selected = selected == InputMethod.SCAN,
            onClick = { onSelect(InputMethod.SCAN) },
            label = { Text(stringResource(R.string.recover_method_scan)) },
        )
        FilterChip(
            selected = selected == InputMethod.FILE,
            onClick = { onSelect(InputMethod.FILE) },
            label = { Text(stringResource(R.string.recover_method_file)) },
        )
    }
}

@Composable
private fun WordEntryPanel(onSubmit: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    val problems = remember(text) { spellcheckBytewords(text).filterNot { it.recognized } }
    val ready = text.isNotBlank() && problems.isEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.recover_word_help),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.recover_word_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        problems.forEach { problem ->
            Text(
                text = if (problem.suggestions.isEmpty()) {
                    stringResource(R.string.recover_word_unknown, problem.token)
                } else {
                    stringResource(
                        R.string.recover_word_suggest,
                        problem.token,
                        problem.suggestions.joinToString(", "),
                    )
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = { if (onSubmit(text)) text = "" },
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.recover_add_shard))
        }
    }
}

@Composable
private fun CameraScannerPanel(onText: (String) -> Boolean) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // Plain remember (not saveable): leaving the Scan tab closes the camera, and
    // returning starts from the Start button rather than silently re-opening it.
    var scanning by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        scanning = granted
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !hasPermission -> {
                Text(
                    text = stringResource(R.string.recover_camera_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.recover_camera_grant))
                }
            }
            scanning -> {
                Text(
                    text = stringResource(R.string.recover_scan_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CameraPreview(onDecoded = { onText(it) })
                OutlinedButton(
                    onClick = { scanning = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.recover_scan_close))
                }
            }
            else -> {
                OutlinedButton(
                    onClick = { scanning = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.recover_scan_start))
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(lifecycleOwner) {
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(executor, QrFrameAnalyzer { text ->
                        // Analyzer runs off the main thread; hop back before
                        // touching ViewModel/Compose state.
                        previewView.post { onDecoded(text) }
                    })
                }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
            )
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun FileImportPanel(onText: (String) -> Boolean, onImageBytes: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val isImage = context.contentResolver.getType(uri)?.startsWith("image/") == true
            val cap = if (isImage) MAX_IMAGE_BYTES else MAX_TEXT_BYTES
            // Over-cap picks read as null and are ignored rather than processed.
            val bytes = context.contentResolver.openInputStream(uri)?.use { readCapped(it, cap) }
            if (bytes != null) {
                if (isImage) onImageBytes(bytes) else onText(bytes.toString(Charsets.UTF_8))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.recover_file_help),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { launcher.launch("*/*") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.recover_pick_file))
        }
    }
}
