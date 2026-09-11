package com.padssh.app.ui.sftp

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.padssh.app.R
import com.padssh.app.ssh.SshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.RemoteResourceInfo
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(
    sshManager: SshManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(".") }
    var entries by remember { mutableStateOf<List<RemoteResourceInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload(p: String = path) {
        scope.launch {
            loading = true
            error = null
            try {
                val list = sshManager.listRemote(p)
                path = p
                entries = list.filter { it.name != "." && it.name != ".." }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val sftp = sshManager.openSftp()
            val home = try {
                sftp.canonicalize(".")
            } catch (_: Exception) {
                "."
            }
            reload(home)
        } catch (e: Exception) {
            error = e.message
            loading = false
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            try {
                val name = queryDisplayName(context, uri) ?: "upload.bin"
                val tmp = File(context.cacheDir, name)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    } ?: error("无法读取文件")
                }
                val remote = if (path.endsWith("/")) "$path$name" else "$path/$name"
                sshManager.uploadFile(tmp, remote)
                tmp.delete()
                Toast.makeText(context, context.getString(R.string.file_uploaded), Toast.LENGTH_SHORT).show()
                reload()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "上传失败", Toast.LENGTH_LONG).show()
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.sftp))
                        Text(path, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val parent = path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
                        if (parent.isNotEmpty()) reload(parent) else reload("/")
                    }) {
                        Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.parent_dir))
                    }
                    IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.upload))
                    }
                    IconButton(onClick = { reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
            }
            error != null -> {
                Text(
                    error ?: "",
                    Modifier.padding(padding).padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            entries.isEmpty() -> {
                Text(
                    stringResource(R.string.empty_dir),
                    Modifier.padding(padding).padding(16.dp),
                )
            }
            else -> {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    items(entries, key = { it.path }) { item ->
                        ListItem(
                            headlineContent = { Text(item.name) },
                            supportingContent = {
                                Text(if (item.isDirectory) "目录" else formatSize(item.attributes.size))
                            },
                            leadingContent = {
                                Icon(
                                    if (item.isDirectory) Icons.Default.Folder else Icons.Filled.InsertDriveFile,
                                    contentDescription = null,
                                )
                            },
                            trailingContent = {
                                if (!item.isDirectory) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            try {
                                                val dest = File(
                                                    context.getExternalFilesDir(null) ?: context.filesDir,
                                                    item.name,
                                                )
                                                sshManager.downloadFile(item.path, dest)
                                                Toast.makeText(
                                                    context,
                                                    "${context.getString(R.string.file_downloaded)}: ${dest.absolutePath}",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download))
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (item.isDirectory) reload(item.path)
                                },
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(size: Long): String {
    if (size < 1024) return "$size B"
    val kb = size / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && idx >= 0) return c.getString(idx)
    }
    return uri.lastPathSegment
}
