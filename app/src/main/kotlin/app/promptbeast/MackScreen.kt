package app.promptbeast

import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
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

    // the page's file inputs: the view-model holds the request, this launches the system picker and reports back
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> vm.mackFileResult(r.resultCode, r.data) }
    val req = vm.mackFileRequest
    LaunchedEffect(req) {
        if (req != null) {
            vm.mackFileLaunched()
            try { picker.launch(req) } catch (e: Exception) { vm.mackFileDeliver(null) }      // no file manager: the page hears "nothing chosen"
        }
    }
    if (vm.signInOpen) SignInDialog(vm)

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
                TextButton(onClick = { vm.signInShow() }, contentPadding = pad, modifier = Modifier.testTag("mack_signin")) { Text("Sign-in") }
                TextButton(onClick = { vm.mackReload() }, contentPadding = pad, modifier = Modifier.testTag("mack_reload")) { Text("Reload") }
                TextButton(onClick = { vm.mackOpenInBrowser() }, contentPadding = pad, modifier = Modifier.testTag("mack_browser")) { Text("Browser") }
            }
            if (vm.mackDiag.isNotEmpty()) {
                SelectionContainer {
                    Text(vm.mackDiag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                        maxLines = 4, modifier = Modifier.padding(top = 2.dp).testTag("mack_diag"))
                }
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

/** One saved Perchance account, kept encrypted on this device and typed into the page's sign-in form on demand. */
@Composable
private fun SignInDialog(vm: PbViewModel) {
    AlertDialog(
        onDismissRequest = { vm.signInOpen = false },
        title = { Text("Perchance sign-in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (vm.signInSaved) "Saved on this device, encrypted. Open Perchance's sign-in in the pane, then press Fill."
                     else "Save your account once; it is encrypted with a key that stays in this device's secure storage.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = vm.signInUser, onValueChange = { vm.signInUser = it }, singleLine = true,
                    label = { Text("Account name or email") }, modifier = Modifier.fillMaxWidth().testTag("signin_user"))
                OutlinedTextField(value = vm.signInPass, onValueChange = { vm.signInPass = it }, singleLine = true,
                    label = { Text(if (vm.signInSaved) "New password (blank keeps the saved one)" else "Password") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("signin_pass"))
            }
        },
        confirmButton = {
            Row {
                if (vm.signInSaved) TextButton(onClick = { vm.signInOpen = false; vm.mackSignIn() }, modifier = Modifier.testTag("signin_fill")) { Text("Fill") }
                TextButton(onClick = { vm.signInSave() }, modifier = Modifier.testTag("signin_save")) { Text("Save") }
            }
        },
        dismissButton = {
            Row {
                if (vm.signInSaved) TextButton(onClick = { vm.signInForget() }, modifier = Modifier.testTag("signin_forget")) { Text("Forget") }
                TextButton(onClick = { vm.signInOpen = false }) { Text("Close") }
            }
        },
    )
}
