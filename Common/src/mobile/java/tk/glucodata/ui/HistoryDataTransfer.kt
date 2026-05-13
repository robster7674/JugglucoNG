package tk.glucodata.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.data.ExportPackageExporter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryExportSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daysText by remember { mutableStateOf("30") }
    var isExporting by remember { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            isExporting = true
            scope.launch {
                val isMmol = Natives.getunit() == 1
                val days = daysText.toLongOrNull() ?: 30L
                val endTime = System.currentTimeMillis()
                val startTime = endTime - (days * 24 * 60 * 60 * 1000L)
                val data = tk.glucodata.data.GlucoseRepository().getHistory(startTime, isMmol)
                val unit = if (isMmol) "mmol/L" else "mg/dL"
                val success = tk.glucodata.data.HistoryExporter.exportToCsv(
                    context = context,
                    uri = uri,
                    data = data,
                    unit = unit,
                    startMillis = startTime,
                    endMillis = endTime
                )

                withContext(Dispatchers.Main) {
                    isExporting = false
                    Toast.makeText(
                        context,
                        if (success) context.getString(R.string.export_successful) else context.getString(R.string.export_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    onDismiss()
                }
            }
        }
    }

    val textLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            isExporting = true
            scope.launch {
                val isMmol = Natives.getunit() == 1
                val days = daysText.toLongOrNull() ?: 30L
                val endTime = System.currentTimeMillis()
                val startTime = endTime - (days * 24 * 60 * 60 * 1000L)
                val data = tk.glucodata.data.GlucoseRepository().getHistory(startTime, isMmol)
                val unit = if (isMmol) "mmol/L" else "mg/dL"
                val success = tk.glucodata.data.HistoryExporter.exportToReadable(
                    context = context,
                    uri = uri,
                    data = data,
                    unit = unit,
                    startMillis = startTime,
                    endMillis = endTime
                )

                withContext(Dispatchers.Main) {
                    isExporting = false
                    Toast.makeText(
                        context,
                        if (success) context.getString(R.string.export_successful) else context.getString(R.string.export_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    onDismiss()
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.export_data),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (isExporting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                OutlinedTextField(
                    value = daysText,
                    onValueChange = { daysText = it },
                    label = { Text(stringResource(R.string.days_to_export)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val fileName = "Juggluco_Export_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(System.currentTimeMillis())}.csv"
                        csvLauncher.launch(fileName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(stringResource(R.string.export_complete_csv))
                }

                OutlinedButton(
                    onClick = {
                        val fileName = "Juggluco_Report_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(System.currentTimeMillis())}.txt"
                        textLauncher.launch(fileName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(stringResource(R.string.export_readable_report))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDataSettingsSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeSettings by rememberSaveable { mutableStateOf(true) }
    var includeHistory by rememberSaveable { mutableStateOf(true) }
    var includeCalibrations by rememberSaveable { mutableStateOf(true) }
    var historyDays by rememberSaveable { mutableStateOf<Long?>(90L) }
    var isExporting by remember { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf<ExportPackageExporter.ExportRequest?>(null) }

    fun currentRequest(): ExportPackageExporter.ExportRequest {
        return ExportPackageExporter.ExportRequest(
            includeSettings = includeSettings,
            includeHistory = includeHistory,
            includeCalibrations = includeCalibrations,
            historyDays = if (includeHistory) historyDays else null
        )
    }

    fun showExportResult(success: Boolean, message: String? = null) {
        Toast.makeText(
            context,
            if (success) {
                context.getString(R.string.export_successful)
            } else {
                context.getString(
                    R.string.export_failed_with_error,
                    message ?: context.getString(R.string.unknown_error)
                )
            },
            Toast.LENGTH_LONG
        ).show()
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val request = pendingRequest
        pendingRequest = null
        if (uri != null && request != null) {
            isExporting = true
            scope.launch {
                val result = ExportPackageExporter.exportToUri(context, uri, request)
                withContext(Dispatchers.Main) {
                    isExporting = false
                    showExportResult(result.isSuccess, result.exceptionOrNull()?.localizedMessage)
                    if (result.isSuccess) onDismiss()
                }
            }
        }
    }

    fun saveToFilePicker() {
        val request = currentRequest()
        if (!request.hasSelection) {
            Toast.makeText(context, context.getString(R.string.export_nothing_selected), Toast.LENGTH_SHORT).show()
            return
        }
        pendingRequest = request
        saveLauncher.launch(ExportPackageExporter.suggestedFileName(request))
    }

    fun shareExport() {
        val request = currentRequest()
        if (!request.hasSelection || isExporting) {
            Toast.makeText(context, context.getString(R.string.export_nothing_selected), Toast.LENGTH_SHORT).show()
            return
        }
        isExporting = true
        scope.launch {
            val result = ExportPackageExporter.writeToCache(context, request)
            withContext(Dispatchers.Main) {
                isExporting = false
                result
                    .onSuccess { cached ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            cached.file
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = cached.mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(
                                    intent,
                                    context.getString(R.string.export_share_to_cloud)
                                )
                            )
                            onDismiss()
                        }.onFailure { throwable ->
                            showExportResult(false, throwable.localizedMessage)
                        }
                    }
                    .onFailure { throwable ->
                        showExportResult(false, throwable.localizedMessage)
                    }
            }
        }
    }

    val allSelected = includeSettings && includeHistory && includeCalibrations

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.export_data_settings),
                style = MaterialTheme.typography.headlineSmall
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = allSelected,
                    onClick = {
                        includeSettings = true
                        includeHistory = true
                        includeCalibrations = true
                    },
                    label = { Text(stringResource(R.string.export_everything)) }
                )
                FilterChip(
                    selected = includeHistory && !includeSettings && !includeCalibrations,
                    onClick = {
                        includeSettings = false
                        includeHistory = true
                        includeCalibrations = false
                    },
                    label = { Text(stringResource(R.string.export_data)) }
                )
                FilterChip(
                    selected = includeSettings && !includeHistory && !includeCalibrations,
                    onClick = {
                        includeSettings = true
                        includeHistory = false
                        includeCalibrations = false
                    },
                    label = { Text(stringResource(R.string.settings)) }
                )
                FilterChip(
                    selected = includeCalibrations && !includeSettings && !includeHistory,
                    onClick = {
                        includeSettings = false
                        includeHistory = false
                        includeCalibrations = true
                    },
                    label = { Text(stringResource(R.string.calibrations)) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ExportContentRow(
                    title = stringResource(R.string.settings),
                    subtitle = stringResource(R.string.export_settings_desc),
                    icon = Icons.Default.Settings,
                    checked = includeSettings,
                    onCheckedChange = { includeSettings = it }
                )
                ExportContentRow(
                    title = stringResource(R.string.export_data),
                    subtitle = stringResource(R.string.export_history_data_desc),
                    icon = Icons.Default.History,
                    checked = includeHistory,
                    onCheckedChange = { includeHistory = it }
                )
                ExportContentRow(
                    title = stringResource(R.string.calibrations),
                    subtitle = stringResource(R.string.export_calibrations_desc),
                    icon = Icons.Default.TrackChanges,
                    checked = includeCalibrations,
                    onCheckedChange = { includeCalibrations = it }
                )
            }

            if (includeHistory) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Text(
                    text = stringResource(R.string.export_range_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportRangeChip(
                        selected = historyDays == 30L,
                        label = stringResource(R.string.export_range_30_days),
                        onClick = { historyDays = 30L }
                    )
                    ExportRangeChip(
                        selected = historyDays == 90L,
                        label = stringResource(R.string.export_range_90_days),
                        onClick = { historyDays = 90L }
                    )
                    ExportRangeChip(
                        selected = historyDays == 365L,
                        label = stringResource(R.string.export_range_365_days),
                        onClick = { historyDays = 365L }
                    )
                    ExportRangeChip(
                        selected = historyDays == null,
                        label = stringResource(R.string.export_range_all),
                        onClick = { historyDays = null }
                    )
                }
            }

            if (isExporting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = ::saveToFilePicker,
                    enabled = !isExporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.export_save_to_files))
                }
                OutlinedButton(
                    onClick = ::shareExport,
                    enabled = !isExporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.export_share_to_cloud))
                }
            }
        }
    }
}

@Composable
private fun ExportContentRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun ExportRangeChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}
