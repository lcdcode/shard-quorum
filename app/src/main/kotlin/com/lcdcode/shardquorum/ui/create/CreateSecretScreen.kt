package com.lcdcode.shardquorum.ui.create

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lcdcode.shardquorum.R
import com.lcdcode.shardquorum.ui.QrImage

@Composable
fun CreateSecretScreen(onExit: () -> Unit, viewModel: CreateSecretViewModel = viewModel()) {
    val shards = viewModel.shards
    if (shards == null) {
        BackHandler {
            viewModel.reset()
            onExit()
        }
        ParamsForm(viewModel)
    } else {
        // Back from the shard pages returns to the form, keeping the inputs.
        BackHandler { viewModel.discardShards() }
        ShardPager(
            shards = shards,
            onDone = {
                viewModel.reset()
                onExit()
            },
        )
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
            onValueChange = { viewModel.name = it },
            label = { Text(stringResource(R.string.create_name_label)) },
            supportingText = { Text(stringResource(R.string.create_name_hint)) },
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

@Composable
private fun ShardPager(shards: List<ShardPage>, onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { shards.size })
    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            ShardPageContent(shards[page])
        }
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.create_done))
        }
    }
}

@Composable
private fun ShardPageContent(shard: ShardPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.shard_page_title, shard.index, shard.count),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.shard_page_scheme, shard.threshold, shard.count),
            style = MaterialTheme.typography.bodyMedium,
        )
        QrImage(
            content = shard.shareUrForQr,
            contentDescription = stringResource(R.string.shard_qr_description),
            modifier = Modifier.fillMaxWidth(0.7f),
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
                modifier = Modifier.fillMaxWidth(0.7f),
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
