package com.pixelpal.app.presentation.screens.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelpal.app.data.remote.bridge.RelayCrypto
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SecondaryButton

/**
 * The primary connect screen: type 9 characters, done.
 *
 * Design intent — a beginner should never see an IP address, a port, or a key.
 * `pixelpal-bridge setup` prints `ABC-DEF-GHI`; that is the entire handshake.
 * QR scanning stays available as a secondary path but is no longer the default.
 */
@Composable
fun CodePairingCard(
    state: CodePairState,
    onPair: (String) -> Unit,
    onScanQr: () -> Unit
) {
    var raw by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val normalized = RelayCrypto.normalizeCode(raw)
    val isWorking = state is CodePairState.Working

    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Connect your computer",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Run `pixelpal-bridge setup` on your laptop, then type the " +
                    "9-character code it shows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))

            CodeField(
                value = raw,
                onValueChange = { updated ->
                    // Keep only code characters so the field can never contain
                    // something that fails validation for a silly reason.
                    raw = updated.uppercase().filter { it.isLetterOrDigit() }.take(9)
                },
                isError = state is CodePairState.Error,
                enabled = !isWorking,
                modifier = Modifier.focusRequester(focusRequester)
            )

            Spacer(Modifier.height(14.dp))

            when (state) {
                is CodePairState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                }

                is CodePairState.Success -> {
                    Text(
                        text = if (state.agentsFound > 0) {
                            "Connected — ${state.agentsFound} agent(s) found"
                        } else {
                            "Connected. Start an agent and it will appear here."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                }

                else -> Spacer(Modifier.height(2.dp))
            }

            PrimaryButton(
                text = if (isWorking) "Connecting…" else "Connect",
                onClick = { onPair(raw) },
                enabled = normalized != null && !isWorking,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onScanQr) { Text("Scan a code instead") }
            }
        }
    }
}

/**
 * A single boxed field that renders the code as `ABC-DEF-GHI`.
 *
 * One field rather than nine boxes on purpose: pasting works, backspace works,
 * and there is no confusing "which box is focused" state.
 */
@Composable
private fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val grouped = remember(value) {
        value.chunked(3).joinToString("-")
    }
    val placeholder = "ABC-DEF-GHI"

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = TextStyle(
                        fontSize = 26.sp,
                        letterSpacing = 4.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            BasicTextField(
                value = grouped,
                onValueChange = { typed ->
                    // Strip the grouping dashes we add before reporting upward.
                    onValueChange(typed.replace("-", "").replace(" ", ""))
                },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 26.sp,
                    letterSpacing = 4.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    }
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.primary
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (RelayCrypto.normalizeCode(value) != null) onValueChange(value)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (value.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                value.length < 9 -> "${9 - value.length} more character(s)"
                else -> " "
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
