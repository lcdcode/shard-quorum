package com.lcdcode.shardquorum.ui.recover

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lcdcode.shardquorum.R
import com.lcdcode.shardquorum.qr.QrFrameAnalyzer
import com.lcdcode.shardquorum.qr.ZxingQrDecoder
import java.util.concurrent.Executors

private enum class InputMethod { WORDS, SCAN, FILE }

@Composable
fun RecoverScreen(onExit: () -> Unit, viewModel: RecoverViewModel = viewModel()) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val result = viewModel.result
        if (result != null) {
            BackHandler {
                viewModel.reset()
                onExit()
            }
            RecoveredView(result, onDone = {
                viewModel.reset()
                onExit()
            })
        } else {
            BackHandler {
                viewModel.reset()
                onExit()
            }
            CollectionScreen(viewModel)
        }
    }
}

@Composable
private fun CollectionScreen(viewModel: RecoverViewModel) {
    var method by remember { mutableStateOf(InputMethod.WORDS) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.recover_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        val threshold = viewModel.threshold
        Text(
            text = if (threshold == null) {
                stringResource(R.string.recover_progress_empty)
            } else {
                stringResource(R.string.recover_progress, viewModel.shares.size, threshold)
            },
            style = MaterialTheme.typography.bodyLarge,
        )

        if (viewModel.collected.isNotEmpty()) {
            CollectedShardsRow(viewModel)
        }

        if (viewModel.hasEnvelope) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.recover_envelope_added),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = viewModel::clearEnvelope) {
                    Text(stringResource(R.string.recover_clear_envelope))
                }
            }
        }

        HorizontalDivider()

        MethodSelector(method, onSelect = { method = it })
        when (method) {
            InputMethod.WORDS -> WordEntry(viewModel)
            InputMethod.SCAN -> CameraScanner(viewModel)
            InputMethod.FILE -> FilePicker(viewModel)
        }

        viewModel.error?.let {
            Text(
                text = stringResource(it.messageRes()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider()

        Button(
            onClick = viewModel::recover,
            enabled = viewModel.canRecover,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.recover_button))
        }
    }
}

@Composable
private fun CollectedShardsRow(viewModel: RecoverViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        viewModel.collected.forEach { shard ->
            AssistChip(
                onClick = { viewModel.removeShardAt(shard.memberIndex) },
                label = {
                    Text(stringResource(R.string.recover_shard_chip, shard.memberIndex + 1))
                },
            )
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
private fun WordEntry(viewModel: RecoverViewModel) {
    var text by remember { mutableStateOf("") }
    val checks = remember(text) { RecoverViewModel.spellcheck(text) }
    val problems = checks.filterNot { it.recognized }
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
            onClick = {
                if (viewModel.addBundle(text)) text = ""
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.recover_add_shard))
        }
    }
}

@Composable
private fun CameraScanner(viewModel: RecoverViewModel) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasPermission) {
            Text(
                text = stringResource(R.string.recover_scan_help),
                style = MaterialTheme.typography.bodyMedium,
            )
            CameraPreview(onDecoded = { viewModel.addBundle(it) })
        } else {
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
            .height(300.dp),
    )
}

@Composable
private fun FilePicker(viewModel: RecoverViewModel) {
    val context = LocalContext.current
    val decoder = remember { ZxingQrDecoder() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val mime = context.contentResolver.getType(uri)
                if (mime?.startsWith("image/") == true) {
                    viewModel.addFromImage(bytes, decoder)
                } else {
                    viewModel.addBundle(bytes.toString(Charsets.UTF_8))
                }
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

@Composable
private fun RecoveredView(result: RecoveredSecret, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.recovered_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.recovered_warning),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = result.display,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (result.isHex) FontFamily.Monospace else FontFamily.Default,
        )
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recovered_done))
        }
    }
}

private fun RecoverError.messageRes(): Int = when (this) {
    RecoverError.UNRECOGNIZED_INPUT -> R.string.recover_error_unrecognized
    RecoverError.DIFFERENT_SPLIT -> R.string.recover_error_different_split
    RecoverError.DUPLICATE_SHARD -> R.string.recover_error_duplicate
    RecoverError.NOT_ENOUGH_SHARDS -> R.string.recover_error_not_enough
    RecoverError.RECOVERY_FAILED -> R.string.recover_error_failed
    RecoverError.IMAGE_DECODE_FAILED -> R.string.recover_error_image_decode
}
