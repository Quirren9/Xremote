package nl.quirren.xremote.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.quirren.xremote.ConnState
import nl.quirren.xremote.Mode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api

private val steps = listOf(
    "Set the X-T3's shutter dial, ISO dial and aperture ring to A for every setting you want the app to control. Settings on a fixed dial show as read-only.",
    "Start wireless communication on the camera (for example by assigning Wireless Communication to an Fn button). It will show a FUJIFILM-X-T3-... network.",
    "Join that network in Android's WiFi settings. Android may warn that it has no internet: stay connected.",
    "Tap Connect. The first time, the camera asks you to accept the device: press OK on the camera.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(state: ConnState, mode: Mode, onConnect: (String, Mode) -> Unit, onShowLog: () -> Unit) {
    val ctx = LocalContext.current
    var host by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable { mutableStateOf(mode) }
    val working = state is ConnState.Working

    Row(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState())) {
            Text("X Remote", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Accent)
            Text("Live view and remote control for Fujifilm X cameras", color = TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            steps.forEachIndexed { i, s ->
                Row(Modifier.padding(bottom = 12.dp)) {
                    Text("${i + 1}", color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                    Text(s, color = TextHi, fontSize = 14.sp, lineHeight = 19.sp)
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("Camera IP (leave empty for auto)") },
                placeholder = { Text("192.168.0.1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Mode.entries.forEach { m ->
                    FilterChip(selected = chosen == m, onClick = { chosen = m }, label = { Text(m.label, fontSize = 12.sp) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onConnect(host, chosen) }, enabled = !working, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (working) CircularProgressIndicator(Modifier.size(22.dp), color = Bg, strokeWidth = 2.dp)
                else Text("Connect", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open WiFi settings") }
            Spacer(Modifier.height(16.dp))
            when (state) {
                is ConnState.Working -> Text(state.message, color = TextHi, fontSize = 14.sp)
                is ConnState.Failed -> Text(state.message, color = Danger, fontSize = 14.sp)
                else -> {}
            }
            TextButton(onClick = onShowLog) { Text("Connection log", color = TextDim) }
        }
    }
}
