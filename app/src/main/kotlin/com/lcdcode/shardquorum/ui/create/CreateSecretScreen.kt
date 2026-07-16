package com.lcdcode.shardquorum.ui.create

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lcdcode.shardquorum.R
import com.lcdcode.shardquorum.export.RecoveryKit
import com.lcdcode.shardquorum.qr.QrPng
import com.lcdcode.shardquorum.qr.ZxingQrDecoder
import com.lcdcode.shardquorum.ui.QrImage
import com.lcdcode.shardquorum.ui.components.ShardInputPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CreateSecretScreen(onExit: () -> Unit, viewModel: CreateSecretViewModel = viewModel()) {
    val exit = {
        viewModel.reset()
        onExit()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (viewModel.phase) {
            CreatePhase.FORM -> {
                BackHandler(onBack = exit)
                ParamsForm(viewModel)
            }
            CreatePhase.RECORD -> ShardViewer(
                shards = viewModel.shards.orEmpty(),
                onContinue = viewModel::startVerify,
                onAbandon = exit,
                savedShards = viewModel.savedShards,
                onShardSaved = viewModel::markShardSaved,
            )
            CreatePhase.VERIFY -> VerifyStep(
                viewModel = viewModel,
                onFinish = exit,
                onBack = viewModel::backToRecord,
            )
        }
    }
}

@Composable
private fun ParamsForm(viewModel: CreateSecretViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.create_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        OutlinedTextField(
            value = viewModel.name,
            onValueChange = {
                if (it.length <= CreateSecretViewModel.MAX_NAME_LENGTH) viewModel.name = it
            },
            label = { Text(stringResource(R.string.create_name_label)) },
            supportingText = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.create_name_warning),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${viewModel.name.length}/${CreateSecretViewModel.MAX_NAME_LENGTH}")
                }
            },
            singleLine = true,
            isError = viewModel.error == CreateError.NAME_REQUIRED,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = viewModel.secretInput,
            onValueChange = { viewModel.secretInput = it },
            label = { Text(stringResource(R.string.create_secret_label_text)) },
            placeholder = { Text(stringResource(R.string.create_secret_placeholder)) },
            supportingText = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.create_secret_supporting),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${viewModel.secretByteCount}/${CreateSecretViewModel.MAX_SECRET_LENGTH}",
                        color = if (viewModel.secretByteCount > CreateSecretViewModel.MAX_SECRET_LENGTH)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            isError = viewModel.error == CreateError.SECRET_REQUIRED
                || viewModel.error == CreateError.SECRET_TOO_LONG,
            modifier = Modifier.fillMaxWidth(),
        )

        PresetSelector(viewModel)
        Text(
            text = stringResource(
                R.string.create_scheme_summary, viewModel.threshold, viewModel.shareCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        viewModel.error?.let {
            Text(
                text = it.message(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(onClick = viewModel::generate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.create_generate))
        }
    }
}

@Composable
private fun QuorumStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = { onChange(value - 1) }) { Text("-") }
        Text(text = label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        OutlinedButton(onClick = { onChange(value + 1) }) { Text("+") }
    }
}

private data class Preset(val threshold: Int, val shareCount: Int, val labelRes: Int, val descRes: Int)

private val PRESETS = listOf(
    Preset(3, 5, R.string.create_preset_3of5, R.string.create_preset_3of5_desc),
    Preset(5, 7, R.string.create_preset_5of7, R.string.create_preset_5of7_desc),
)

@Composable
private fun PresetCard(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    description: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PresetSelector(viewModel: CreateSecretViewModel) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.selectableGroup(),
    ) {
        PRESETS.forEach { preset ->
            val selected = preset.threshold == viewModel.threshold &&
                preset.shareCount == viewModel.shareCount
            PresetCard(
                selected = selected,
                onClick = { viewModel.selectPreset(preset.threshold, preset.shareCount) },
                label = stringResource(preset.labelRes),
                description = stringResource(preset.descRes),
            )
        }

        TextButton(
            onClick = viewModel::toggleCustomQuorum,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.create_preset_custom))
        }

        if (viewModel.showCustomQuorum) {
            QuorumStepper(
                label = stringResource(R.string.create_threshold_label, viewModel.threshold),
                value = viewModel.threshold,
                onChange = viewModel::setThresholdClamped,
            )
            QuorumStepper(
                label = stringResource(R.string.create_share_count_label, viewModel.shareCount),
                value = viewModel.shareCount,
                onChange = viewModel::setShareCountClamped,
            )
        }
    }
}

/**
 * Shows one shard at a time with Previous/Next navigation. [onContinue] advances
 * to the verify step; system back is guarded by an abandon confirmation, since
 * leaving discards the shards (they are shown only once).
 */
@Composable
private fun ShardViewer(
    shards: List<ShardPage>,
    onContinue: () -> Unit,
    onAbandon: () -> Unit,
    savedShards: Set<Int>,
    onShardSaved: (Int) -> Unit,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    var showSaveOptions by rememberSaveable { mutableStateOf(false) }
    val current = shards[index]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Guards the async save builds against a double-tap launching two.
    var exporting by remember { mutableStateOf(false) }

    // CreateDocument hands back a destination uri; we write the payload that was
    // staged just before launching, then clear it. The shard index is staged
    // alongside so the shard is marked saved only after its bytes actually land
    // (cancelling the picker returns uri == null and marks nothing).
    var pendingPng by remember { mutableStateOf<ByteArray?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingZip by remember { mutableStateOf<ByteArray?>(null) }
    var pendingShardIndex by remember { mutableStateOf(0) }
    // Writes go through SAF, whose provider may be network-backed (a cloud
    // documents app), so every write runs on the IO dispatcher.
    fun writeToUri(uri: Uri, bytes: ByteArray, shardIndex: Int) {
        scope.launch(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri) ?: return@launch
            stream.use { it.write(bytes) }
            onShardSaved(shardIndex)
        }
    }
    val savePngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val bytes = pendingPng
        pendingPng = null
        if (uri != null && bytes != null) writeToUri(uri, bytes, pendingShardIndex)
    }
    val saveZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val bytes = pendingZip
        pendingZip = null
        if (uri != null && bytes != null) writeToUri(uri, bytes, pendingShardIndex)
    }
    val saveTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = pendingText
        pendingText = null
        if (uri != null && text != null) {
            writeToUri(uri, text.toByteArray(Charsets.UTF_8), pendingShardIndex)
        }
    }

    val baseName = "shardquorum-shard-${current.index}-of-${current.count}"

    // The shard QR sheet: the shard code plus the recovery envelope code, so a
    // saved image always carries everything.
    fun buildShardPng(): ByteArray {
        val sections = buildList {
            add(
                QrPng.LabeledQr(
                    context.getString(R.string.png_label_shard, current.index, current.count),
                    current.shareUrForQr,
                ),
            )
            current.envelopeUrForQr?.let {
                add(QrPng.LabeledQr(context.getString(R.string.png_label_envelope), it))
            }
        }
        return QrPng.encodeSheet(current.secretName, sections)
    }

    // The recovery kit: this recipient's shard (QR sheet + words) bundled with
    // the offline recovery tool, spec, vectors and README shipped in the APK, so
    // each recipient receives a complete, app-independent way to rebuild later.
    fun buildKit(): ByteArray = RecoveryKit.buildKit(
        assets = context.assets,
        shardPng = buildShardPng(),
        shardText = CreateSecretViewModel.shareText(current),
        index = current.index,
        count = current.count,
        secretName = current.secretName,
    )

    // Rendering the QR sheet (bitmap draw + PNG compress) takes long enough to
    // drop frames, so save payloads are built on Default, then handed to the SAF
    // picker via the pending* slot the launcher callback consumes.
    fun saveStaged(build: () -> ByteArray, stage: (ByteArray) -> Unit, launchPicker: () -> Unit) {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                stage(withContext(Dispatchers.Default) { build() })
                launchPicker()
            } finally {
                exporting = false
            }
        }
    }

    BackHandler { showConfirm = true }

    if (showConfirm) {
        ConfirmAbandonDialog(
            onConfirm = {
                showConfirm = false
                onAbandon()
            },
            onDismiss = { showConfirm = false },
        )
    }

    if (showSaveOptions) {
        SaveOptionsDialog(
            onSaveKit = {
                showSaveOptions = false
                pendingShardIndex = current.index
                saveStaged(::buildKit, { pendingZip = it }) {
                    saveZipLauncher.launch("$baseName-kit.zip")
                }
            },
            onSaveQr = {
                showSaveOptions = false
                pendingShardIndex = current.index
                saveStaged(::buildShardPng, { pendingPng = it }) {
                    savePngLauncher.launch("$baseName.png")
                }
            },
            onSaveWords = {
                showSaveOptions = false
                pendingShardIndex = current.index
                pendingText = CreateSecretViewModel.shareText(current)
                saveTextLauncher.launch("$baseName.txt")
            },
            onDismiss = { showSaveOptions = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.shard_showing, current.index, current.count),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 24.dp, end = 24.dp),
        )

        ShardPageContent(
            shard = current,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        val isCurrentSaved = current.index in savedShards
        val allSaved = savedShards.size == shards.size

        if (isCurrentSaved) {
            Text(
                text = stringResource(
                    R.string.shard_saved_progress,
                    savedShards.size,
                    shards.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = { if (index > 0) index-- },
                enabled = index > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.shard_nav_prev))
            }
            EmphasisButton(
                filled = isCurrentSaved && !allSaved,
                onClick = { if (index < shards.lastIndex) index++ },
                enabled = index < shards.lastIndex,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.shard_nav_next))
            }
        }

        EmphasisButton(
            filled = !isCurrentSaved,
            onClick = { showSaveOptions = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp),
        ) {
            Text(stringResource(R.string.shard_save))
        }

        if (allSaved) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(stringResource(R.string.create_continue_verify))
            }
        }
    }
}

/**
 * Button whose emphasis tracks a state: filled (primary) when [filled],
 * outlined otherwise, with identical behavior in both forms.
 */
@Composable
private fun EmphasisButton(
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (filled) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

@Composable
private fun SaveOptionsDialog(
    onSaveKit: () -> Unit,
    onSaveQr: () -> Unit,
    onSaveWords: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shard_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveKit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shard_save_kit))
                }
                Text(
                    text = stringResource(R.string.shard_save_kit_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                OutlinedButton(onClick = onSaveQr, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shard_save_qr))
                }
                OutlinedButton(onClick = onSaveWords, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shard_save_words))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shard_save_cancel))
            }
        },
    )
}

@Composable
private fun ConfirmAbandonDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shard_abandon_title)) },
        text = { Text(stringResource(R.string.shard_abandon_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.shard_abandon_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shard_abandon_no))
            }
        },
    )
}

@Composable
private fun VerifyStep(
    viewModel: CreateSecretViewModel,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    val decoder = remember { ZxingQrDecoder() }
    var showSkipWarning by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    if (showSkipWarning) {
        AlertDialog(
            onDismissRequest = { showSkipWarning = false },
            title = { Text(stringResource(R.string.verify_skip_title)) },
            text = { Text(stringResource(R.string.verify_skip_message)) },
            confirmButton = {
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.verify_skip_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipWarning = false }) {
                    Text(stringResource(R.string.verify_skip_cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.verify_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.verify_instructions, viewModel.threshold),
            style = MaterialTheme.typography.bodyMedium,
        )

        val verifyCount = viewModel.verifyShares.size
        var showVerifySuccess by remember { mutableStateOf(false) }
        var prevVerifyCount by remember { mutableIntStateOf(0) }
        LaunchedEffect(verifyCount) {
            if (verifyCount > prevVerifyCount) {
                showVerifySuccess = true
                delay(2000)
                showVerifySuccess = false
            }
            prevVerifyCount = verifyCount
        }

        when (viewModel.verifyState) {
            VerifyState.COLLECTING -> Text(
                text = stringResource(
                    R.string.verify_progress,
                    verifyCount,
                    viewModel.threshold,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            VerifyState.VERIFIED -> Text(
                text = stringResource(R.string.verify_ok),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
            VerifyState.MISMATCH -> Text(
                text = stringResource(R.string.verify_mismatch),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (viewModel.verifyState != VerifyState.VERIFIED) {
            ShardInputPanel(
                onText = { viewModel.addVerifyText(it) },
                onImageBytes = { viewModel.addVerifyImage(it, decoder) },
                busy = viewModel.isDecoding,
            )
            viewModel.verifyError?.let {
                Text(
                    text = stringResource(it.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showVerifySuccess,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.recover_shard_imported),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        HorizontalDivider()

        if (viewModel.verifyState == VerifyState.VERIFIED) {
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.verify_finish))
            }
        } else {
            TextButton(onClick = { showSkipWarning = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.verify_skip))
            }
        }
    }
}

private fun VerifyInputError.messageRes(): Int = when (this) {
    VerifyInputError.UNRECOGNIZED -> R.string.recover_error_unrecognized
    VerifyInputError.DIFFERENT_SPLIT -> R.string.verify_error_different_split
    VerifyInputError.IMAGE_DECODE_FAILED -> R.string.recover_error_image_decode
}

@Composable
private fun ShardPageContent(shard: ShardPage, modifier: Modifier = Modifier) {
    // With an envelope QR there are two codes to fit; shrink both so the pair
    // stays on screen without scrolling. A single code can be a little larger.
    val hasEnvelope = shard.envelopeUrForQr != null
    val qrFraction = if (hasEnvelope) 0.5f else 0.62f

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.shard_page_scheme, shard.threshold, shard.count),
            style = MaterialTheme.typography.bodyMedium,
        )
        QrImage(
            content = shard.shareUrForQr,
            contentDescription = stringResource(R.string.shard_qr_description),
            modifier = Modifier.fillMaxWidth(qrFraction),
        )
        Text(
            text = shard.shareBytewords,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
        shard.envelopeUrForQr?.let { envelopeUr ->
            HorizontalDivider()
            Text(
                text = stringResource(R.string.shard_envelope_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.shard_envelope_explanation),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            QrImage(
                content = envelopeUr,
                contentDescription = stringResource(R.string.shard_envelope_qr_description),
                modifier = Modifier.fillMaxWidth(qrFraction),
            )
        }
    }
}

@Composable
private fun CreateError.message(): String = when (this) {
    CreateError.NAME_REQUIRED -> stringResource(R.string.create_error_name_required)
    CreateError.SECRET_REQUIRED -> stringResource(R.string.create_error_secret_required)
    CreateError.SECRET_TOO_LONG -> stringResource(
        R.string.create_error_secret_too_long, CreateSecretViewModel.MAX_SECRET_LENGTH,
    )
}
