package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxFileExplorer
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Linux rootfs 只读文件浏览：目录列举与文件读取都经一次性 root Shell 完成，
 * 查看文件时进入屏内查看态，页面返回键先退回列表再退出页面。
 */
@Composable
internal fun LinuxFilesScreen(
    context: Context,
    distribution: String,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val linuxDistribution = remember(distribution) {
        LinuxDistribution.entries.firstOrNull { it.wireName == distribution }
    }
    val rootfsDir = remember(linuxDistribution) {
        linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it) }
    }
    val installed = rootfsDir != null && LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)

    val shellSupervisor = remember { ShellProcessSupervisor() }
    DisposableEffect(Unit) {
        onDispose { shellSupervisor.beginClosing() }
    }

    var currentPath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<LinuxFileExplorer.Entry>?>(null) }
    var listError by remember { mutableStateOf<Int?>(null) }
    var openFilePath by remember { mutableStateOf<String?>(null) }
    var fileResult by remember { mutableStateOf<LinuxFileExplorer.ReadResult?>(null) }

    LaunchedEffect(currentPath, linuxDistribution) {
        val dir = rootfsDir ?: return@LaunchedEffect
        if (!installed) return@LaunchedEffect
        entries = null
        listError = null
        val result = withContext(Dispatchers.IO) {
            LinuxFileExplorer.list(shellSupervisor, dir, currentPath)
        }
        when (result) {
            is LinuxFileExplorer.ListResult.Success -> entries = result.entries
            LinuxFileExplorer.ListResult.NotDirectory ->
                listError = R.string.linux_files_error_not_directory
            LinuxFileExplorer.ListResult.Unreadable,
            LinuxFileExplorer.ListResult.CommandFailed,
            LinuxFileExplorer.ListResult.NotInstalled ->
                listError = R.string.linux_files_error_unreadable
        }
    }

    LaunchedEffect(openFilePath) {
        val path = openFilePath ?: return@LaunchedEffect
        val dir = rootfsDir ?: return@LaunchedEffect
        fileResult = null
        fileResult = withContext(Dispatchers.IO) {
            LinuxFileExplorer.readText(shellSupervisor, dir, path)
        }
    }

    fun closeFile() {
        openFilePath = null
        fileResult = null
    }

    // 查看文件时系统返回键先退回列表，再退出页面。
    val viewerBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = viewerBackState,
        isBackEnabled = openFilePath != null,
        onBackCompleted = { closeFile() },
    )

    MiuixScaffoldPage(
        title = stringResource(R.string.linux_files_title),
        onBack = { if (openFilePath != null) closeFile() else onBack() },
    ) {
        when {
            linuxDistribution == null -> {
                item(key = "invalid-distribution") {
                    StateMessage(stringResource(R.string.linux_files_invalid_distribution))
                }
            }
            !installed -> {
                item(key = "not-installed") {
                    StateMessage(stringResource(R.string.linux_files_not_installed))
                }
            }
            openFilePath != null -> {
                item(key = "viewer-path") {
                    PathBar(openFilePath.orEmpty())
                }
                when (val result = fileResult) {
                    is LinuxFileExplorer.ReadResult.Text -> {
                        if (result.truncated) {
                            item(key = "viewer-truncated") {
                                HintText(stringResource(R.string.linux_files_truncated_hint))
                            }
                        }
                        item(key = "viewer-content") {
                            SelectionContainer {
                                Text(
                                    text = result.content,
                                    style = MiuixTheme.textStyles.footnote1
                                        .copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                            }
                        }
                    }
                    LinuxFileExplorer.ReadResult.Binary -> {
                        item(key = "viewer-binary") {
                            StateMessage(stringResource(R.string.linux_files_binary_hint))
                        }
                    }
                    LinuxFileExplorer.ReadResult.NotFile -> {
                        item(key = "viewer-not-file") {
                            StateMessage(stringResource(R.string.linux_files_error_not_file))
                        }
                    }
                    LinuxFileExplorer.ReadResult.Unreadable,
                    LinuxFileExplorer.ReadResult.CommandFailed,
                    LinuxFileExplorer.ReadResult.NotInstalled -> {
                        item(key = "viewer-error") {
                            StateMessage(stringResource(R.string.linux_files_error_unreadable))
                        }
                    }
                    null -> Unit
                }
            }
            else -> {
                item(key = "path-bar") {
                    PathBar(currentPath)
                }
                if (currentPath != "/") {
                    item(key = "..") {
                        FileRow(
                            name = "../",
                            isDir = true,
                            summary = null,
                            onClick = {
                                currentPath = currentPath.trimEnd('/')
                                    .substringBeforeLast('/')
                                    .ifBlank { "/" }
                            },
                        )
                    }
                }
                val currentEntries = entries
                when {
                    listError != null -> {
                        item(key = "list-error") {
                            StateMessage(stringResource(listError ?: R.string.linux_files_error_unreadable))
                        }
                    }
                    currentEntries != null && currentEntries.isEmpty() -> {
                        item(key = "list-empty") {
                            StateMessage(stringResource(R.string.linux_files_empty))
                        }
                    }
                    currentEntries != null -> {
                        items(currentEntries, key = { it.name }) { entry ->
                            FileRow(
                                name = entry.name,
                                isDir = entry.isDir,
                                summary = if (entry.isDir) {
                                    null
                                } else {
                                    Formatter.formatShortFileSize(appContext, entry.sizeBytes)
                                },
                                onClick = {
                                    val target = currentPath.trimEnd('/') + "/" + entry.name
                                    if (entry.isDir) {
                                        currentPath = target
                                    } else {
                                        openFilePath = target
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PathBar(path: String) {
    Text(
        text = path,
        style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp),
    )
}

@Composable
private fun StateMessage(message: String) {
    Text(
        text = message,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun HintText(message: String) {
    Text(
        text = message,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp),
    )
}

@Composable
private fun FileRow(
    name: String,
    isDir: Boolean,
    summary: String?,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = name,
        summary = summary,
        startAction = {
            Icon(
                imageVector = if (isDir) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
        onClick = onClick,
    )
}
