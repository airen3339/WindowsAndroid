package com.windowsandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.windowsandroid.DisplayView
import com.windowsandroid.ExternalScreen
import com.windowsandroid.HidKeys
import com.windowsandroid.InputPump
import com.windowsandroid.MachineStore
import com.windowsandroid.VmController
import com.windowsandroid.VmNative
import com.windowsandroid.VmStatus
import kotlinx.coroutines.delay

@Composable
fun SessionScreen(status: VmStatus, store: MachineStore, external: ExternalScreen.Info?, onLeave: () -> Unit) {
    var view by remember { mutableStateOf<DisplayView?>(null) }
    var barVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var keyboardOn by remember { mutableStateOf(false) }
    var trackpad by remember { mutableStateOf(store.trackpadMode) }
    var stats by remember { mutableStateOf(store.showStats) }
    var sticky by remember { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    DisposableEffect(Unit) {
        InputPump.onStickyChanged = { sticky = it }
        onDispose { InputPump.onStickyChanged = null }
    }
    // The bar fades after a few seconds without touching it; a tap on the top edge brings it back.
    LaunchedEffect(barVisible, lastTouch) {
        if (barVisible) { delay(4500); if (System.currentTimeMillis() - lastTouch >= 4400) barVisible = false }
    }
    LaunchedEffect(imeVisible) { if (!imeVisible) keyboardOn = false }
    // Back (the edge swipe) never leaves Windows: it shows and hides the bar.
    BackHandler { barVisible = !barVisible; lastTouch = System.currentTimeMillis() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Windows and, while typing, the extra-keys row share the space above the keyboard.
        Column(Modifier.fillMaxSize().imePadding()) {
            AndroidView(
                factory = { ctx ->
                    DisplayView(ctx).also {
                        it.trackpadMode = trackpad
                        view = it
                    }
                },
                update = { it.trackpadMode = trackpad },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            if (keyboardOn || imeVisible) ExtraKeys(sticky, Modifier)
        }

        if (external != null && status.phase != VmStatus.Phase.Exited) {
            TrackpadHint(external, status, Modifier.align(Alignment.Center).padding(24.dp))
        } else if (status.phase == VmStatus.Phase.Starting || (status.phase == VmStatus.Phase.Running && status.fbWidth == 0)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                WindowsLogo(Modifier.size(56.dp))
                Spacer(Modifier.size(24.dp))
                CircularProgressIndicator(color = Palette.Accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(12.dp))
                Text("Starting the virtual PC…", color = Palette.Muted, fontSize = 13.sp)
            }
        }
        if (status.phase == VmStatus.Phase.Exited) {
            Column(
                Modifier.align(Alignment.Center).background(Palette.Deck, RoundedCornerShape(20.dp)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Windows has shut down", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(16.dp))
                TextButton(onClick = { VmController.exitProcess() }) { Text("Close") }
            }
        }

        AnimatedVisibility(
            visible = barVisible, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
        ) {
            CommandBar(
                trackpad = trackpad, keyboardOn = keyboardOn, stats = stats, external = external != null,
                onAny = { lastTouch = System.currentTimeMillis() },
                onKeyboard = { keyboardOn = !keyboardOn; view?.showKeyboard(keyboardOn) },
                onMode = { trackpad = !trackpad; store.trackpadMode = trackpad },
                onWin = { InputPump.tap(HidKeys.LGUI) },
                onCad = { VmNative.nativeCtrlAltDel() },
                onStats = { stats = !stats; store.showStats = stats },
                onHome = onLeave,
                onPower = { confirmStop = true },
            )
        }

        if (stats && status.phase == VmStatus.Phase.Running) {
            Text(
                "%d×%d  %.0f fps  JIT %.0f/%.0f MB  idle %.0f%%  %.1f MHz%s".format(
                    status.fbWidth, status.fbHeight, status.fps, status.jitUsedMb, status.jitCapMb,
                    status.idlePercent, status.counterMhz,
                    if (status.keyboardReady) "  kbd" else "",
                ),
                color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }


        if (confirmStop) {
            PowerSheet(
                onShutDown = { confirmStop = false; VmController.shutDown() },
                onForce = { VmController.forceStop() },
                onCancel = { confirmStop = false },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun Pill(label: String, active: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color.White else Palette.Text,
        fontSize = 13.sp,
        modifier = Modifier
            .background(if (active) Palette.WindowsBlue else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun CommandBar(
    trackpad: Boolean, keyboardOn: Boolean, stats: Boolean, external: Boolean,
    onAny: () -> Unit, onKeyboard: () -> Unit, onMode: () -> Unit, onWin: () -> Unit, onCad: () -> Unit,
    onStats: () -> Unit, onHome: () -> Unit, onPower: () -> Unit,
) {
    fun wrap(f: () -> Unit): () -> Unit = { onAny(); f() }
    Row(
        Modifier.background(Color(0xE6111A2E), RoundedCornerShape(18.dp))
            .border(1.dp, Palette.Line, RoundedCornerShape(18.dp))
            .padding(4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Pill("⌨ Keyboard", keyboardOn, wrap(onKeyboard))
        if (!external) Pill(if (trackpad) "Trackpad" else "Touch", false, wrap(onMode))
        Pill("⊞ Start", false, wrap(onWin))
        Pill("Ctrl+Alt+Del", false, wrap(onCad))
        Pill("Stats", stats, wrap(onStats))
        Pill("Home", false, wrap(onHome))
        Pill("Power", false, wrap(onPower))
    }
}

/** The phone while Windows is on an external display: a trackpad, and what it is showing. */
@Composable
private fun TrackpadHint(external: ExternalScreen.Info, status: VmStatus, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Windows is on ${external.name}", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
        Text(
            "This screen is the trackpad: drag to move, tap to click, two fingers to scroll or right-click.",
            color = Palette.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        val booting = status.phase == VmStatus.Phase.Starting || status.fbWidth == 0
        val fits = status.fbWidth == external.bootWidth && status.fbHeight == external.bootHeight
        if (!booting && !fits) {
            Text(
                "Windows started at ${status.fbWidth}×${status.fbHeight}. Restart Windows to fill this display " +
                    "at ${external.bootWidth}×${external.bootHeight}.",
                color = Palette.Warn, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

/** The keys a phone keyboard does not have, above it. Modifiers are sticky for the next key. */
@Composable
private fun ExtraKeys(sticky: Int, modifier: Modifier) {
    fun on(usage: Int) = sticky and (1 shl (usage - 0xe0)) != 0
    Row(
        modifier.fillMaxWidth().background(Color(0xF00A0F1C)).horizontalScroll(rememberScrollState()).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Pill("Esc") { InputPump.tap(HidKeys.ESC) }
        Pill("Tab") { InputPump.tap(HidKeys.TAB) }
        Pill("Ctrl", on(HidKeys.LCTRL)) { InputPump.toggleSticky(HidKeys.LCTRL) }
        Pill("Alt", on(HidKeys.LALT)) { InputPump.toggleSticky(HidKeys.LALT) }
        Pill("Shift", on(HidKeys.LSHIFT)) { InputPump.toggleSticky(HidKeys.LSHIFT) }
        Pill("⊞", on(HidKeys.LGUI)) { InputPump.toggleSticky(HidKeys.LGUI) }
        Pill("←") { InputPump.tap(HidKeys.LEFT) }
        Pill("↑") { InputPump.tap(HidKeys.UP) }
        Pill("↓") { InputPump.tap(HidKeys.DOWN) }
        Pill("→") { InputPump.tap(HidKeys.RIGHT) }
        Pill("Home") { InputPump.tap(HidKeys.HOME) }
        Pill("End") { InputPump.tap(HidKeys.END) }
        Pill("Del") { InputPump.tap(HidKeys.DELETE) }
        for (i in 0 until 12) Pill("F${i + 1}") { InputPump.tap(HidKeys.F1 + i) }
    }
}

@Composable
private fun PowerSheet(onShutDown: () -> Unit, onForce: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    Column(
        modifier.background(Palette.Deck, RoundedCornerShape(20.dp)).border(1.dp, Palette.Line, RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Power", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Shut down asks Windows to turn off (like a laptop's power button).\nForce stop ends it now; unsaved work is lost.",
            color = Palette.Muted, fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = onForce) { Text("Force stop", color = Palette.Bad) }
            TextButton(onClick = onShutDown) { Text("Shut down") }
        }
    }
}
