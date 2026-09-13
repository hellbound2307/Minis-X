package com.openminis.app.ui.settings

/*
 * [T-pytools-manager] Management screen for agent-minted Python tools
 * (py_meta_tools). The registry is a live StateFlow — this screen reads it
 * reactively, so a tool minted in ANY chat updates this list instantly
 * without a manual refresh.
 *
 * Sections:
 *   - Tool list: name, description, params count, enabled badge; tap → detail
 *   - Detail sheet: description, schema params, timeout editor (5-300s),
 *     enable toggle, code viewer (monospace, collapsible), delete (confirm
 *     dialog — a pytool is code the agent wrote; deletion is destructive)
 *   - Create sheet: name + code, validates through the SAME write() path
 *     the agent uses (introspection fail-closed) — no second validation
 *     implementation to drift.
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.tools.PyMetaToolStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PyToolsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val tools by PyMetaToolStore.tools.collectAsState()
    var selectedTool by remember { mutableStateOf<String?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = "Minted Tools",
        onBack = onBack,
        actions = {
            IconButton(onClick = { showCreateSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add tool")
            }
        },
    ) {
        SettingsSection(
            header = "Python Tools",
            footer = "Tools the agent mints via py_meta_tools. Registered tools are callable by name in every chat. Toggle a tool off to keep it installed but off the agent surface.",
        ) {
            if (tools.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "No minted tools yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Ask the agent in any chat: \"mint a tool that …\" — it appears here instantly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                tools.sortedBy { it.name.lowercase() }.forEachIndexed { index, tool ->
                    val paramCount = runCatching {
                        tool.schema.optJSONObject("properties")?.length() ?: 0
                    }.getOrDefault(0)
                    SettingsRow(
                        title = tool.name,
                        subtitle = tool.description,
                        icon = Icons.Outlined.Code,
                        iconColor = if (tool.isEnabled) Color(0xFF30B0C7) else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { selectedTool = tool.name },
                        showDivider = index < tools.size - 1,
                        minHeight = 72.dp,
                        trailing = {
                            Text(
                                if (tool.isEnabled) "ON" else "OFF",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (tool.isEnabled) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }

        SettingsSection(
            header = "How it works",
            footer = "Each tool is a Python file with a main(**kwargs) — parameters from basic type annotations (str/int/float/bool), docstring becomes the description. Executed in the PRoot sandbox through the minis-pytools harness with a per-tool timeout. Code lives in filesDir/minis-global/meta-tools.",
        ) {
            SettingsRow(
                title = "Registry",
                subtitle = PyMetaToolStore.LINUX_DIR + "/registry.json",
                showChevron = false,
                showDivider = false,
            )
        }
    }

    // -- Detail sheet --

    selectedTool?.let { name ->
        val tool = tools.firstOrNull { it.name == name }
        if (tool != null) {
            PyToolDetailSheet(
                tool = tool,
                onDismiss = { selectedTool = null },
            )
        } else {
            // Deleted while sheet open — just close.
            selectedTool = null
        }
    }

    if (showCreateSheet) {
        PyToolCreateSheet(
            onDismiss = { showCreateSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PyToolDetailSheet(
    tool: PyMetaToolStore.PyTool,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var showCode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var timeoutText by remember { mutableStateOf(tool.timeoutSeconds.toString()) }

    val code = remember(tool.name) {
        PyMetaToolStore.readCode(context, tool.name) ?: "// code file missing"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(tool.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // -- Schema params --

            val props = tool.schema.optJSONObject("properties")
            val required = runCatching {
                val arr = tool.schema.optJSONArray("required")
                if (arr != null) (0 until arr.length()).map { arr.optString(it) } else emptyList()
            }.getOrDefault(emptyList<String>())

            if (props != null && props.length() > 0) {
                Text(
                    "Parameters",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                val keys = props.keys().asSequence().toList()
                keys.forEach { key ->
                    val type = props.optJSONObject(key)?.optString("type", "string") ?: "string"
                    val req = if (key in required) "*" else ""
                    Text(
                        "$key$req : $type",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "* required",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // -- Enable toggle --

            SettingsSection(
                header = "State",
                modifier = Modifier.padding(top = 12.dp),
            ) {
                SettingsSwitchRow(
                    title = "Enabled",
                    checked = tool.isEnabled,
                    onCheckedChange = { checked ->
                        PyMetaToolStore.updateSettings(
                            context = context,
                            name = tool.name,
                            enabled = checked,
                        )
                    },
                    showDivider = true,
                )
                // Timeout row: text field + apply. coerceIn(5, 300) clamps in
                // the store; the field accepts free text and Apply writes it.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "Timeout (seconds)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = timeoutText,
                            onValueChange = { timeoutText = it.filter { c -> c.isDigit() }.take(3) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            supportingText = { Text("5–300 s") },
                        )
                        TextButton(
                            onClick = {
                                timeoutText.toIntOrNull()?.let { secs ->
                                    val updated = PyMetaToolStore.updateSettings(
                                        context = context,
                                        name = tool.name,
                                        timeoutSeconds = secs,
                                    )
                                    if (updated != null) {
                                        timeoutText = updated.timeoutSeconds.toString()
                                    }
                                }
                            },
                        ) {
                            Text("Apply")
                        }
                    }
                }
            }

            // -- Code viewer --

            Text(
                if (showCode) "Hide code" else "View code",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clickable { showCode = !showCode },
            )
            if (showCode) {
                Text(
                    code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }

            // -- Delete --

            TextButton(
                onClick = { confirmDelete = true },
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Delete tool")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${tool.name}\"?") },
            text = { Text("The agent mints this tool on demand — deleting removes it from every chat's tool surface. Any chat asking for it gets \"Unknown tool\" again.") },
            confirmButton = {
                TextButton(onClick = {
                    PyMetaToolStore.delete(context, tool.name)
                    confirmDelete = false
                    onDismiss()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PyToolCreateSheet(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New tool", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim().lowercase().replace(Regex("[^a-z0-9_]"), "").take(32) },
                label = { Text("Name") },
                supportingText = { Text("3–32 chars: lowercase, digits, _, starts with a letter") },
                singleLine = true,
                isError = name.isNotEmpty() && name.length < 3,
            )

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Python code") },
                supportingText = { Text("Must define main(**kwargs); basic type annotations become the schema.") },
                minLines = 6,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
            )

            if (error != null) {
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.End),
            ) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                TextButton(
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        error = null
                        scope.launch {
                            when (val result = PyMetaToolStore.write(
                                context = context,
                                name = name,
                                code = code,
                                description = null,
                                timeoutSeconds = null,
                                enabled = true,
                            )) {
                                is PyMetaToolStore.WriteResult.Success -> onDismiss()
                                is PyMetaToolStore.WriteResult.Invalid -> {
                                    error = result.message
                                    busy = false
                                }
                                is PyMetaToolStore.WriteResult.Error -> {
                                    error = result.message
                                    busy = false
                                }
                            }
                        }
                    },
                    enabled = !busy && name.length >= 3 && code.contains("def main"),
                ) { Text(if (busy) "Validating…" else "Create") }
            }
        }
    }
}
