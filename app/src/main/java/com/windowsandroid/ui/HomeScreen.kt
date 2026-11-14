package com.windowsandroid.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windowsandroid.MachineStore
import com.windowsandroid.VmController
import com.windowsandroid.VmStatus
import com.windowsandroid.install.WindowsInstall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun HomeScreen(status: VmStatus, store: MachineStore, onStart: () -> Unit, onResume: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf<String?>(null) }
    var importedBytes by remember { mutableStateOf(0L) }

    fun importTo(dest: File) = { uri: Uri? ->
        if (uri != null) scope.launch {
            importing = dest.name
            importedBytes = 0
            val err = withContext(Dispatchers.IO) {
                runCatching { store.import(uri, dest) { importedBytes = it } }.exceptionOrNull()
            }
            importing = if (err != null) "failed: ${err.message}" else null
            refresh++
        }
        Unit
    }
    val pickCode = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), importTo(store.code))
    val pickVars = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), importTo(store.vars))
    val pickDisk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment.orEmpty()
            importTo(if (name.endsWith(".hvsparse")) store.sparseDisk else store.rawDisk)(uri)
        }
    }

    val hasWindows = store.hasDisk || store.needsUnpack
    val busy = status.phase != VmStatus.Phase.Idle

    Box(
        Modifier.fillMaxSize().background(Palette.Night).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            key(refresh) {
                // No Windows yet: the steps to get it. Otherwise: the one button that starts it.
                if (store.installing || !hasWindows) InstallCard(store, status, onChanged = { refresh++ })
                if (hasWindows || store.installing || busy) StatusCard(status, store, onStart, onResume)
            }
            Collapsible("Settings", "Processor, memory, internet, sound") { SettingsContent(store) }
            Collapsible("Advanced", "Use a Windows disk made on a computer") {
                key(refresh) {
                    MachineFiles(store, importing, importedBytes,
                        onPickCode = { pickCode.launch(arrayOf("*/*")) },
                        onPickVars = { pickVars.launch(arrayOf("*/*")) },
                        onPickDisk = { pickDisk.launch(arrayOf("*/*")) },
                        onRescan = { refresh++ })
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WindowsLogo(Modifier.size(40.dp))
        Spacer(Modifier.size(14.dp))
        Column {
            Text("WindowsAndroid", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Text("Windows 11 on your phone", color = Palette.Muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun WindowsLogo(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(2) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) { Box(Modifier.weight(1f).fillMaxSize().background(Palette.WindowsBlue)) }
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Palette.Deck, RoundedCornerShape(18.dp))
            .border(1.dp, Palette.Line, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = Palette.Text, fontSize = 16.sp)
        content()
    }
}

/** A card that starts closed: the things most people never need to touch. */
@Composable
private fun Collapsible(title: String, sub: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(Palette.Deck, RoundedCornerShape(18.dp))
            .border(1.dp, Palette.Line, RoundedCornerShape(18.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Palette.Text, fontSize = 16.sp)
                if (!open) Text(sub, color = Palette.Muted, fontSize = 12.sp)
            }
            Text(if (open) "\u25B4" else "\u25BE", color = Palette.Muted, fontSize = 18.sp)
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun FileRow(label: String, file: File, ok: Boolean, note: String, onPick: () -> Unit, okNote: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (ok) Palette.Good else Palette.Warn, CircleShape))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.Text, fontSize = 14.sp)
            Text(
                if (ok) okNote ?: "${file.name} · ${human(file.length())}" else note,
                color = Palette.Muted, fontSize = 12.sp,
            )
        }
        OutlinedButton(onClick = onPick) { Text(if (ok && okNote == null) "Replace" else "Import") }
    }
}

@Composable
private fun MachineFiles(
    store: MachineStore, importing: String?, importedBytes: Long,
    onPickCode: () -> Unit, onPickVars: () -> Unit, onPickDisk: () -> Unit, onRescan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "The files the virtual PC runs from. Installing Windows above sets these up by itself; " +
                "this is for importing a disk prepared on a computer instead.",
            color = Palette.Muted, fontSize = 12.sp,
        )
        if (store.hasCustomFirmware) {
            FileRow("UEFI firmware", store.code, true, "", onPickCode)
        } else {
            FileRow("UEFI firmware", store.code, store.hasFirmware, "Built in · or import a code.fd to use instead", onPickCode, okNote = "Built in (EDK2 ArmVirtQemu)")
        }
        FileRow("UEFI variables", store.vars, store.hasVars, "vars.fd · created blank on first start if missing", onPickVars)
        val disk = if (store.hasDisk) store.rawDisk else store.sparseDisk
        FileRow(
            "Windows 11 disk", disk, store.hasDisk || store.needsUnpack,
            "windows11.img or .hvsparse · or install Windows above", onPickDisk,
        )
        if (importing != null) {
            Text("Importing $importing… ${human(importedBytes)}", color = Palette.Accent, fontSize = 12.sp)
        }
        Text(
            "Or copy them in with adb:\nadb push vars.fd windows11.hvsparse ${store.root.absolutePath.replace("/storage/emulated/0", "/sdcard")}/",
            color = Palette.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
        OutlinedButton(onClick = onRescan) { Text("Rescan") }
        Text(
            "Log: ${store.logFile.absolutePath}",
            color = Palette.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

/** Install Windows from Microsoft's Arm64 ISO: no computer, no disk image to copy in. */
@Composable
private fun InstallCard(store: MachineStore, status: VmStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checked by remember { mutableStateOf<WindowsInstall.Checked?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var user by remember { mutableStateOf(store.installUser) }
    val pickIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            problem = null
            when (val c = withContext(Dispatchers.IO) { WindowsInstall.check(context, uri) }) {
                is WindowsInstall.Checked.Ok -> {
                    WindowsInstall.choose(context, store, uri)
                    checked = c
                    onChanged()
                }
                is WindowsInstall.Checked.Bad -> problem = c.why
            }
        }
    }
    val iso = store.installIso
    LaunchedEffect(iso) {
        if (iso != null && checked == null) checked = withContext(Dispatchers.IO) { WindowsInstall.check(context, Uri.parse(iso)) }
    }
    val idle = status.phase == VmStatus.Phase.Idle

    val uriHandler = LocalUriHandler.current
    Card("Get Windows 11") {
        if (iso == null) {
            Step(1, "Download Windows 11 from Microsoft",
                "On Microsoft's page, under \"Download Windows 11 Disk Image (ISO) for Arm64\", pick your language and download it to this phone. It's about 6 GB.")
            OutlinedButton(onClick = { uriHandler.openUri(MICROSOFT_DOWNLOAD) }) { Text("Open Microsoft's download page") }
            Step(2, "Choose the file you downloaded", "It ends in .iso and is usually in Downloads.")
            Button(
                onClick = { pickIso.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*")) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.WindowsBlue),
            ) { Text("Choose the file") }
            Step(3, "Install", "Windows then installs by itself. It needs about 25 GB of free space.")
            if (store.freeBytes < 25L shl 30) {
                Text("This phone has only ${human(store.freeBytes)} free.", color = Palette.Warn, fontSize = 12.sp)
            }
        } else {
            when (val c = checked) {
                is WindowsInstall.Checked.Ok -> Text(
                    "Windows 11 · ${Locale.forLanguageTag(c.info.language).displayName} · ${human(c.size)}",
                    color = Palette.Text, fontSize = 14.sp,
                )
                is WindowsInstall.Checked.Bad -> Text(c.why, color = Palette.Bad, fontSize = 13.sp)
                null -> Text("Checking the ISO…", color = Palette.Muted, fontSize = 13.sp)
            }
            val fresh = store.diskIsBlank()
            if (fresh) {
                OutlinedTextField(
                    value = user, enabled = idle, singleLine = true,
                    onValueChange = { user = it.take(20); store.installUser = AnswerDiscName.clean(user) },
                    label = { Text("Your name in Windows") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "No password: Windows signs you in by itself. Windows isn't activated; " +
                        "add your own product key in Windows' Settings afterwards.",
                    color = Palette.Muted, fontSize = 12.sp,
                )
                if (store.freeBytes < 25L shl 30) {
                    Text("This phone has only ${human(store.freeBytes)} free; Windows needs about 25 GB.", color = Palette.Warn, fontSize = 12.sp)
                }
            } else {
                Text(
                    "Windows has started installing. Tap Continue installing to let it finish.",
                    color = Palette.Muted, fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = idle, onClick = { pickIso.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*")) }) {
                    Text("Different file")
                }
                if (!fresh) {
                    TextButton(enabled = idle, onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.createBlankDisk(WindowsInstall.DISK_BYTES) }
                            onChanged()
                        }
                    }) { Text("Start over") }
                }
                TextButton(enabled = idle, onClick = {
                    WindowsInstall.finish(context, store)
                    if (fresh) store.rawDisk.delete() // nothing on it yet
                    checked = null
                    onChanged()
                }) { Text(if (fresh) "Cancel" else "It's finished installing") }
            }
        }
        problem?.let { Text(it, color = Palette.Bad, fontSize = 13.sp) }
    }
}

@Composable
private fun Step(n: Int, title: String, sub: String) {
    Row {
        Box(
            Modifier.size(24.dp).background(Palette.Panel, CircleShape).border(1.dp, Palette.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = Palette.Text, fontSize = 12.sp) }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Text, fontSize = 14.sp)
            Text(sub, color = Palette.Muted, fontSize = 12.sp)
        }
    }
}

private const val MICROSOFT_DOWNLOAD = "https://www.microsoft.com/software-download/windows11arm64"

private object AnswerDiscName {
    fun clean(s: String) = com.windowsandroid.install.AnswerDisc.cleanUser(s)
}

@Composable
private fun SettingsContent(store: MachineStore) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var cpus by remember { mutableFloatStateOf(store.cpus.toFloat()) }
        var ram by remember { mutableFloatStateOf(store.ramMb.toFloat()) }
        LabeledSlider("Processor cores", "${cpus.toInt()} of ${store.hostCores}", cpus, 1f..minOf(8, store.hostCores).toFloat(),
            steps = minOf(8, store.hostCores) - 2) {
            cpus = it; store.cpus = it.toInt()
        }
        LabeledSlider(
            "Memory", "${(ram / 1024f).let { "%.1f".format(it) }} GB of ${"%.0f".format(store.deviceRamMb / 1024f)} GB",
            ram, 1024f..store.maxRamMb.toFloat(),
        ) {
            ram = (it / 256f).toInt() * 256f; store.ramMb = ram.toLong()
        }
        Toggle("Internet", "Windows uses your phone's connection", store.network) { store.network = it }
        Toggle("Sound and microphone", "Windows plays through your phone and can use its microphone", store.sound) { store.sound = it }
        Toggle("Shared folder", "A folder both Windows and your phone can open", store.shareFolder) { store.shareFolder = it }
        Toggle("Sign in with your phone", "Google sign-ins and passkeys open in your phone's browser", store.openLinksOnPhone) {
            VmController.setOpenLinksOnPhone(it)
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = Palette.Muted, fontSize = 13.sp)
        }
        Slider(value = v, onValueChange = onChange, valueRange = range, steps = steps.coerceAtLeast(0))
    }
}

@Composable
private fun Toggle(label: String, sub: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.Text, fontSize = 14.sp)
            Text(sub, color = Palette.Muted, fontSize = 12.sp)
        }
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}

@Composable
private fun StatusCard(status: VmStatus, store: MachineStore, onStart: () -> Unit, onResume: () -> Unit) {
    Card("Windows") {
        when (status.phase) {
            VmStatus.Phase.Idle -> {
                val label = when {
                    !store.installing -> "Start Windows"
                    store.diskIsBlank() -> "Install Windows"
                    else -> "Continue installing Windows"
                }
                Button(
                    onClick = onStart, enabled = store.hasFirmware,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.WindowsBlue),
                ) { Text(label, fontSize = 16.sp) }
                if (!store.hasFirmware) Text("Something is missing from the app; reinstall it.", color = Palette.Warn, fontSize = 12.sp)
            }
            VmStatus.Phase.Unpacking -> {
                Text(status.message, color = Palette.Text)
                LinearProgressIndicator(progress = { status.unpackProgress.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            VmStatus.Phase.Starting, VmStatus.Phase.Running -> {
                Text("Windows is running", color = Palette.Good)
                if (status.installing) {
                    Text(
                        "Installing Windows. It restarts a few times on its own; leave it running until you see the Windows desktop. " +
                            "Keep this app open and your phone charging: in the background, Android slows it down a lot.",
                        color = Palette.Muted, fontSize = 12.sp,
                    )
                }
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("Return to Windows") }
            }
            VmStatus.Phase.Exited -> {
                Text(status.message, color = Palette.Muted)
                Button(onClick = { VmController.exitProcess() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
            VmStatus.Phase.Failed -> {
                Text(status.message, color = Palette.Bad)
                Button(onClick = { VmController.exitProcess() }, modifier = Modifier.fillMaxWidth()) { Text("Close and try again") }
            }
        }
    }
}

private fun human(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
    else -> "%d KB".format(bytes / 1024)
}
