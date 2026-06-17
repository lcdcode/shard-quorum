package com.lcdcode.shardquorum.ui.recover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lcdcode.shardquorum.R
import com.lcdcode.shardquorum.qr.ZxingQrDecoder
import com.lcdcode.shardquorum.ui.components.ShardInputPanel

@Composable
fun RecoverScreen(onExit: () -> Unit, viewModel: RecoverViewModel = viewModel()) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BackHandler {
            viewModel.reset()
            onExit()
        }
        val result = viewModel.result
        if (result != null) {
            RecoveredView(result, onDone = {
                viewModel.reset()
                onExit()
            })
        } else {
            CollectionScreen(viewModel)
        }
    }
}

@Composable
private fun CollectionScreen(viewModel: RecoverViewModel) {
    val decoder = remember { ZxingQrDecoder() }

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

        ShardInputPanel(
            onText = { viewModel.addBundle(it) },
            onImageBytes = { viewModel.addFromImage(it, decoder) },
        )

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
