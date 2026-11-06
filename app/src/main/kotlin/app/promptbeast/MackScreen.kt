package app.promptbeast

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Box

/**
 * Mack's McGenerator in an embedded page, with the three bridge actions on top. The WebView itself lives in the view-model
 * (so the page, and whatever Mack has typed into it, survives tab switches and rotation); this screen only attaches it.
 */
@Composable
fun MackScreen(vm: PbViewModel) {
    LaunchedEffect(Unit) { vm.mackEnsure() }
    DisposableEffect(Unit) {
        vm.mackPause(false)
        onDispose { vm.mackPause(true) }
    }

    val failed = vm.mackState == "failed"
    val live = vm.mackState == "live"

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            // one tight row of actions, one row of switches: the page needs the room
            val pad = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.mackFill() }, enabled = live, contentPadding = pad,
                    modifier = Modifier.weight(1f).testTag("mack_fill")) { Text("Fill", maxLines = 1) }
                Button(onClick = { vm.mackFillAndGenerate() }, enabled = live, contentPadding = pad,
                    modifier = Modifier.weight(1.4f).testTag("mack_fill_go")) { Text("Fill & go", maxLines = 1) }
                OutlinedButton(onClick = { vm.mackPull() }, enabled = live, contentPadding = pad,
                    modifier = Modifier.weight(1f).testTag("mack_pull")) { Text("Pull", maxLines = 1) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.mackPad, onCheckedChange = { vm.mackPad = it },
                    modifier = Modifier.scale(0.75f).testTag("mack_pad"))
                Text("Scratchpad", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.mackReload() }, contentPadding = pad, modifier = Modifier.testTag("mack_reload")) { Text("Reload") }
                TextButton(onClick = { vm.mackOpenInBrowser() }, contentPadding = pad, modifier = Modifier.testTag("mack_browser")) { Text("Browser") }
            }
            if (vm.mackState == "loading") {
                LinearProgressIndicator(progress = { vm.mackProgress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val view = vm.mackView
            if (view != null && !failed) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().testTag("mack_web"),
                    factory = { host ->
                        vm.mackAttach(host)
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        view
                    },
                    onRelease = {
                        (it.parent as? ViewGroup)?.removeView(it)
                        vm.mackDetach()
                    },
                )
            }
            if (failed) MackFallback(vm)
        }
    }
}

/** The page can't load (offline, no WebView on the device, Mack down): the Mack key still works by hand. */
@Composable
private fun MackFallback(vm: PbViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("mack_fallback"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PbCard {
            Label("Mack isn't loaded")
            Gap(4)
            Text(vm.mackNote.ifEmpty { "The embedded page isn't available." })
            Gap(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.copyMackNow() }, modifier = Modifier.testTag("mack_copy_key")) { Text("Copy Mack key") }
                OutlinedButton(onClick = { vm.mackReload() }, modifier = Modifier.testTag("mack_retry")) { Text("Try again") }
                TextButton(onClick = { vm.mackOpenInBrowser() }) { Text("Open in browser") }
            }
        }
        Dim("In your own browser: open Mack's McGenerator, then paste the Mack key into its import box.")
    }
}
