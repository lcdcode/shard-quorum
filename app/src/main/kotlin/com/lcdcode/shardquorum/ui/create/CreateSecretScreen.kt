package com.lcdcode.shardquorum.ui.create

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lcdcode.shardquorum.R
import com.lcdcode.shardquorum.export.RecoveryKit
import com.lcdcode.shardquorum.qr.QrPng
import com.lcdcode.shardquorum.qr.ZxingQrDecoder
import com.lcdcode.shardquorum.ui.QrImage
import com.lcdcode.shardquorum.ui.components.ShardInputPanel
import java.io.File

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

        ModeSelector(viewModel)

        OutlinedTextField(
            value = viewModel.secretInput,
            onValueChange = { viewModel.secretInput = it },
            label = {
                Text(
                    stringResource(
                        when (viewModel.mode) {
                            SecretMode.KEK -> R.string.create_secret_label_text
                            SecretMode.DIRECT -> R.string.create_secret_label_hex
                        },
                    ),
                )
            },
            isError = viewModel.error in listOf(
                CreateError.SECRET_REQUIRED, CreateError.HEX_INVALID, CreateError.HEX_LENGTH,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

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
        Text(
            text = stringResource(
                R.string.create_scheme_summary, viewModel.threshold, viewModel.shareCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        viewModel.error?.let {
            Text(
                text = stringResource(it.messageRes()),
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
private fun ModeSelector(viewModel: CreateSecretViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = viewModel.mode == SecretMode.KEK,
            onClick = { viewModel.mode = SecretMode.KEK },
            label = { Text(stringResource(R.string.create_mode_kek)) },
        )
        FilterChip(
            selected = viewModel.mode == SecretMode.DIRECT,
            onClick = { viewModel.mode = SecretMode.DIRECT },
            label = { Text(stringResource(R.string.create_mode_direct)) },
        )
    }
    Text(
        text = stringResource(
            when (viewModel.mode) {
                SecretMode.KEK -> R.string.create_mode_kek_description
                SecretMode.DIRECT -> R.string.create_mode_direct_description
            },
        ),
        style = MaterialTheme.typography.bodySmall,
    )
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

/**
 * Shows one shard at a time with Previous/Next navigation. [onContinue] advances
 * to the verify step; system back is guarded by an abandon confirmation, since
 * leaving discards the shards (they are shown only once).
 */
@Composable
private fun ShardViewer(shards: List<ShardPage>, onContinue: () -> Unit, onAbandon: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    var showShareWarning by rememberSaveable { mutableStateOf(false) }
    var shareWarningAcknowledged by rememberSaveable { mutableStateOf(false) }
    var showShareOptions by rememberSaveable { mutableStateOf(false) }
    var showSaveOptions by rememberSaveable { mutableStateOf(false) }
    val current = shards[index]
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Staged shard PNGs (for the share intent) hold key material, so they must
    // not linger on disk. We purge them once we regain the foreground - by then
    // the recipient's read window via the granted URI has passed - and again
    // when this screen is left, as a backstop.
    val sharedDir = remember(context) { File(context.cacheDir, "shared") }
    val purgeSharedDir = { sharedDir.listFiles()?.forEach { it.delete() }; Unit }
    var pendingShareCleanup by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingShareCleanup) {
                purgeSharedDir()
                pendingShareCleanup = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            purgeSharedDir()
        }
    }

    // CreateDocument hands back a destination uri; we write the payload that was
    // staged just before launching, then clear it.
    var pendingPng by remember { mutableStateOf<ByteArray?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingZip by remember { mutableStateOf<ByteArray?>(null) }
    val savePngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val bytes = pendingPng
        pendingPng = null
        if (uri != null && bytes != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        }
    }
    val saveZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val bytes = pendingZip
        pendingZip = null
        if (uri != null && bytes != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        }
    }
    val saveTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = pendingText
        pendingText = null
        if (uri != null && text != null) {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    val baseName = "shardquorum-shard-${current.index}-of-${current.count}"

    // The shard QR sheet: the shard code plus, in KEK mode, the recovery
    // envelope code, so a saved or shared image always carries everything.
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
    )

    fun chooserFor(send: Intent) {
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.shard_share_chooser)),
        )
    }

    fun shareWords() {
        chooserFor(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, CreateSecretViewModel.shareText(current))
            },
        )
    }

    fun shareQrImage() {
        // Stage into cache/shared, keeping only the current shard. The file is
        // purged when we return to the foreground and when this screen is left
        // (see the DisposableEffect above), so it does not outlive the share.
        sharedDir.mkdirs()
        purgeSharedDir()
        val file = File(sharedDir, "$baseName.png").apply { writeBytes(buildShardPng()) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingShareCleanup = true
        chooserFor(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    fun shareKit() {
        // Same transient-staging discipline as shareQrImage: the kit carries the
        // shard (key material), so it is purged on return to foreground and on exit.
        sharedDir.mkdirs()
        purgeSharedDir()
        val file = File(sharedDir, "$baseName-kit.zip").apply { writeBytes(buildKit()) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingShareCleanup = true
        chooserFor(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
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

    if (showShareWarning) {
        ShareWarningDialog(
            onConfirm = {
                showShareWarning = false
                shareWarningAcknowledged = true
                showShareOptions = true
            },
            onDismiss = { showShareWarning = false },
        )
    }

    if (showShareOptions) {
        ShareOptionsDialog(
            onShareKit = {
                showShareOptions = false
                shareKit()
            },
            onShareQr = {
                showShareOptions = false
                shareQrImage()
            },
            onShareWords = {
                showShareOptions = false
                shareWords()
            },
            onDismiss = { showShareOptions = false },
        )
    }

    if (showSaveOptions) {
        SaveOptionsDialog(
            onSaveKit = {
                showSaveOptions = false
                pendingZip = buildKit()
                saveZipLauncher.launch("$baseName-kit.zip")
            },
            onSaveQr = {
                showSaveOptions = false
                pendingPng = buildShardPng()
                savePngLauncher.launch("$baseName.png")
            },
            onSaveWords = {
                showSaveOptions = false
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
            OutlinedButton(
                onClick = { if (index < shards.lastIndex) index++ },
                enabled = index < shards.lastIndex,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.shard_nav_next))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = { showSaveOptions = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.shard_save))
            }
            OutlinedButton(
                onClick = {
                    if (shareWarningAcknowledged) showShareOptions = true else showShareWarning = true
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.shard_share))
            }
        }

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
private fun ShareOptionsDialog(
    onShareKit: () -> Unit,
    onShareQr: () -> Unit,
    onShareWords: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shard_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShareKit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shard_share_kit))
                }
                Text(
                    text = stringResource(R.string.shard_share_kit_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                OutlinedButton(onClick = onShareQr, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shard_share_qr))
                }
                OutlinedButton(onClick = onShareWords, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shard_share_words))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shard_share_cancel))
            }
        },
    )
}

@Composable
private fun ShareWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shard_share_warning_title)) },
        text = { Text(stringResource(R.string.shard_share_warning_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.shard_share_warning_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shard_share_warning_cancel))
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

        when (viewModel.verifyState) {
            VerifyState.COLLECTING -> Text(
                text = stringResource(
                    R.string.verify_progress,
                    viewModel.verifyShares.size,
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
            )
            viewModel.verifyError?.let {
                Text(
                    text = stringResource(it.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
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

private fun CreateError.messageRes(): Int = when (this) {
    CreateError.NAME_REQUIRED -> R.string.create_error_name_required
    CreateError.SECRET_REQUIRED -> R.string.create_error_secret_required
    CreateError.HEX_INVALID -> R.string.create_error_hex_invalid
    CreateError.HEX_LENGTH -> R.string.create_error_hex_length
}
