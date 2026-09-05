package io.github.mangi.eta.ui.screens.terminal

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.WorkspaceEntry
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { WorkspaceFileStore(context) }
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf("") }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var publicAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        publicAccess = Environment.isExternalStorageManager()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                var succeeded = 0
                uris.forEach { if (store.importFile(it)) succeeded++ }
                message = if (succeeded == uris.size) context.getString(R.string.capability_workspace_imported)
                else context.getString(R.string.capability_workspace_partial_import, succeeded)
                if (succeeded > 0) path = "imports"
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.capability_workspace_failed)
            } finally {
                busy = false
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri != null && source != null) scope.launch {
            busy = true
            try {
                store.exportFile(source, uri)
                message = context.getString(R.string.capability_workspace_exported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.capability_workspace_failed)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(path, revision) {
        try {
            entries = store.list(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            entries = emptyList()
            message = context.getString(R.string.capability_workspace_failed)
        }
    }
    MiuixScaffoldPage(title = stringResource(R.string.capability_workspace), onBack = onBack) {
        item(key = "workspace-info") {
            BasicComponent(
                title = stringResource(R.string.capability_workspace_private),
                summary = stringResource(R.string.capability_workspace_private_summary),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        item(key = "actions") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.capability_workspace_import),
                    enabled = !busy,
                    startAction = { PreferenceIcon(Icons.Rounded.DriveFolderUpload, enabled = !busy) },
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )

                ArrowPreference(
                    title = stringResource(R.string.capability_workspace_public),
                    startAction = { PreferenceIcon(Icons.Rounded.FolderOpen) },
                    summary = if (publicAccess) stringResource(R.string.capability_workspace_public_granted) else stringResource(R.string.capability_workspace_public_summary),
                    onClick = {
                        try {
                            accessLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")))
                        } catch (_: android.content.ActivityNotFoundException) {
                            message = context.getString(R.string.capability_workspace_failed)
                        }
                    },
                )
            }
        }
        message?.let { text -> item(key = "message") { BasicComponent(title = text) } }
        item(key = "path") {
            SmallTitle(if (path.isBlank()) stringResource(R.string.capability_workspace_files) else path)
        }
        if (path.isNotBlank()) {
            item(key = "parent") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.capability_workspace_parent),
                        startAction = { PreferenceIcon(Icons.Rounded.FolderOpen) },
                        onClick = { path = path.substringBeforeLast('/', "") },
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.capability_workspace_empty),
                    summary = stringResource(R.string.capability_workspace_empty_summary),
                )
            }
        }
        items(entries, key = { it.path }) { entry ->
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                ArrowPreference(
                    title = entry.name,
                    summary = if (entry.directory) stringResource(R.string.capability_workspace_directory)
                        else stringResource(
                            R.string.capability_workspace_file_export,
                            Formatter.formatShortFileSize(context, entry.size),
                        ),
                    startAction = {
                        PreferenceIcon(
                            icon = if (entry.directory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                            enabled = !busy,
                        )
                    },
                    enabled = !busy,
                    onClick = {
                        if (entry.directory) path = entry.path else {
                            pendingExport = entry.path
                            exportLauncher.launch(entry.name)
                        }
                    },
                )
            }
        }
    }
}
