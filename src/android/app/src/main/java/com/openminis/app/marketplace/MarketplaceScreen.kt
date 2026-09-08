package com.openminis.app.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.plugins.PluginManager
import kotlinx.coroutines.launch

/**
 * [T-android-marketplace-ui] Marketplace screen — browse the curated catalog
 * and install with one tap.
 *
 * State machine: loading → list → (installing per-entry) → snackbar-ish
 * status line. Install runs the full kernel pipeline (staging, hooks,
 * permission-diff gate, netguard arming) via [MarketplaceTools.executeInstall].
 * A permission-diff refusal is surfaced verbatim — the diff IS the message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<MarketplaceTools.Entry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var installingId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmInstall by remember { mutableStateOf<MarketplaceTools.Entry?>(null) }

    fun refresh(force: Boolean) {
        loading = true
        error = null
        scope.launch {
            try {
                entries = MarketplaceTools.catalogEntries(force)
            } catch (e: Exception) {
                error = e.message
            }
            loading = false
        }
    }

    // Initial load.
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh(false) }

    fun doInstall(entry: MarketplaceTools.Entry) {
        installingId = entry.id
        status = null
        scope.launch {
            // Synthetic session id: the staging shell + install hooks run in
            // their own PRoot shell instance keyed by this id. The plugin's
            // payload lands app-side and is mounted for ALL sessions, so the
            // synthetic session never matters after install completes.
            val result = MarketplaceTools.executeInstall(
                argsJson = """{"id":"${entry.id}"}""",
                sessionId = "marketplace-ui",
            )
            installingId = null
            status = result.output
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Plugin Marketplace") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh(true) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh catalog")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search catalog") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when {
                loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Fetching catalog…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Catalog unavailable", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(error ?: "", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { refresh(true) }) { Text("Retry") }
                    }
                }
                else -> {
                    val filtered = entries.filter { e ->
                        query.isBlank() || e.id.contains(query, ignoreCase = true) ||
                            e.name.contains(query, ignoreCase = true) ||
                            e.description.contains(query, ignoreCase = true) ||
                            e.tags.any { it.contains(query, ignoreCase = true) }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Text(
                                "Curated plugins — permissions declared, netguard-enforced. " +
                                    "Install runs offline staging + hooks; updates that expand " +
                                    "permissions are refused.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(filtered, key = { it.id }) { entry ->
                            val installed = PluginManager.get(entry.id)
                            MarketplaceCard(
                                entry = entry,
                                installedVersion = installed?.let { if (it.status == "installed") it.manifest.version else null },
                                installing = installingId == entry.id,
                                onInstall = { confirmInstall = entry },
                            )
                        }
                        if (filtered.isEmpty()) {
                            item {
                                Text(
                                    "Nothing matches \"$query\".",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                    }
                }
            }

            status?.let { s ->
                Text(
                    s,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
        }
    }

    confirmInstall?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmInstall = null },
            title = { Text("Install ${entry.name}?") },
            text = {
                Text(
                    "v${entry.version} by ${entry.author}\n\n" +
                        entry.description + "\n\n" +
                        "The kernel validates the manifest, runs declared install hooks inside " +
                        "the sandbox, and enforces the plugin's network allowlist (netguard). " +
                        "You can disable or uninstall anytime.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmInstall = null
                    doInstall(entry)
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MarketplaceCard(
    entry: MarketplaceTools.Entry,
    installedVersion: String?,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.size(6.dp))
                        if (entry.verified) {
                            Text(
                                "✓ verified",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        "${entry.id} · v${entry.version} · ${entry.author}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (installedVersion != null) {
                    Text(
                        "installed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(entry.description, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when {
                    installing -> CircularProgressIndicator(Modifier.size(22.dp))
                    installedVersion != null -> {}
                    else -> Button(onClick = onInstall) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Install")
                    }
                }
            }
        }
    }
}
