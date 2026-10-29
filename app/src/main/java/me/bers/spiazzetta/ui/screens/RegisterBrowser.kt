package me.bers.spiazzetta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import me.bers.spiazzetta.R
import me.bers.spiazzetta.domain.RegisterEntry

@Composable
fun RegisterBrowserDialog(
    stoveName: String,
    registers: List<RegisterEntry>,
    onExport: (json: String) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.registers_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.registers_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(registers, key = { it.group + "/" + it.type + "/" + it.offset }) { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.group + " · " + entry.type,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "@" + entry.offset + " mask " + entry.mask,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = (entry.label ?: entry.decoded?.let { format(entry) } ?: "-") +
                                    (entry.raw?.let { " (" + it + ")" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(registersToJson(stoveName, registers)) }) {
                Text(stringResource(R.string.registers_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        },
    )
}

private fun format(entry: RegisterEntry): String {
    val decoded = entry.decoded ?: return "-"
    return if (decoded == Math.floor(decoded)) decoded.toInt().toString() else String.format(java.util.Locale.US, "%.2f", decoded)
}

fun registersToJson(stoveName: String, registers: List<RegisterEntry>): String {
    val body = registers.joinToString(",\n") { entry ->
        "  {\"group\":\"${escape(entry.group)}\",\"type\":\"${escape(entry.type)}\"," +
            "\"offset\":${entry.offset},\"mask\":${entry.mask},\"raw\":${entry.raw ?: "null"}," +
            "\"decoded\":${entry.decoded ?: "null"},\"label\":${entry.label?.let { "\"" + escape(it) + "\"" } ?: "null"}," +
            "\"writable\":${entry.writable}}"
    }
    return "{\n \"stove\":\"${escape(stoveName)}\",\n \"registers\":[\n" + body + "\n ]\n}"
}

private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
