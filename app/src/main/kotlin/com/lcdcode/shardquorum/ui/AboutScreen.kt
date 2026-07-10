package com.lcdcode.shardquorum.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lcdcode.shardquorum.R

@Composable
fun AboutScreen(onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Paragraph(R.string.about_intro)

            Section(R.string.about_sss_heading, R.string.about_sss_body)
            Section(R.string.about_sskr_heading, R.string.about_sskr_body)
            Section(R.string.about_envelope_heading, R.string.about_envelope_body)

            HorizontalDivider()

            Heading(R.string.about_use_heading)
            Paragraph(R.string.about_use_create)
            Paragraph(R.string.about_use_distribute)
            Paragraph(R.string.about_use_verify)
            Paragraph(R.string.about_use_recover)

            HorizontalDivider()

            Section(R.string.about_safety_heading, R.string.about_safety_body)
            Section(R.string.about_interop_heading, R.string.about_interop_body)
            Section(R.string.about_quantum_heading, R.string.about_quantum_body)

            OutlinedButton(
                onClick = onExit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.about_back))
            }
        }
    }
}

@Composable
private fun Section(headingRes: Int, bodyRes: Int) {
    Heading(headingRes)
    Paragraph(bodyRes)
}

@Composable
private fun Heading(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun Paragraph(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
    )
}
