package nl.quirren.xremote.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.quirren.xremote.ConnState
import java.util.Locale

/**
 * Start screen in the same body as the shooting screen: leatherette left, an empty viewfinder
 * in the middle with one status line, the silver top plate right with CONNECT on the shutter.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ConnectScreen(state: ConnState, onConnect: (String) -> Unit, onConnectUsb: () -> Unit, onStop: () -> Unit, onShowLog: () -> Unit) {
    val ctx = LocalContext.current
    val working = state is ConnState.Working

    Row(Modifier.fillMaxSize().background(Lens)) {
        // Leatherette side with the name engraved
        Column(
            Modifier.width(138.dp).fillMaxHeight().leatherette().padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("X", color = Engrave, fontSize = 46.sp, fontWeight = FontWeight.Black)
            Text("REMOTE", color = Engrave, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Spacer(Modifier.weight(1f))
            Text("X-T3", color = Engrave.copy(alpha = 0.45f), fontSize = 10.sp, letterSpacing = 3.sp)
        }

        // Viewfinder: frame corners and one line of status
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(28.dp)) {
                val k = 22.dp.toPx(); val sw = 1.5.dp.toPx(); val c = Engrave.copy(alpha = 0.35f)
                val w = size.width; val h = size.height
                listOf(
                    Offset(0f, 0f) to Offset(k, 0f), Offset(0f, 0f) to Offset(0f, k),
                    Offset(w, 0f) to Offset(w - k, 0f), Offset(w, 0f) to Offset(w, k),
                    Offset(0f, h) to Offset(k, h), Offset(0f, h) to Offset(0f, h - k),
                    Offset(w, h) to Offset(w - k, h), Offset(w, h) to Offset(w, h - k),
                ).forEach { (a, b) -> drawLine(c, a, b, sw) }
                // centre cross, like the framing guide
                val m = Offset(w / 2, h / 2); val r = 8.dp.toPx()
                drawLine(c, Offset(m.x - r, m.y), Offset(m.x + r, m.y), sw)
                drawLine(c, Offset(m.x, m.y - r), Offset(m.x, m.y + r), sw)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 90.dp)) {
                when (state) {
                    is ConnState.Working -> {
                        CircularProgressIndicator(color = Engrave, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(state.message.substringBefore('\n').uppercase(Locale.US),
                            style = evfText.copy(letterSpacing = 1.5.sp, textAlign = TextAlign.Center),
                            modifier = Modifier.widthIn(max = 420.dp))
                    }
                    is ConnState.Failed -> {
                        Text("NOT CONNECTED", style = evfText.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
                        Spacer(Modifier.height(6.dp))
                        Text(state.message.substringBefore(". ").substringBefore('\n'),
                            style = evfText.copy(fontSize = 11.sp, color = Engrave.copy(alpha = 0.6f), textAlign = TextAlign.Center),
                            maxLines = 2, modifier = Modifier.widthIn(max = 420.dp))
                    }
                    else -> Text("NOT CONNECTED", style = evfText.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
                }
            }
        }

        // Silver top plate: the shutter connects
        Column(
            Modifier.width(118.dp).fillMaxHeight().brushedSilver().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            BodyButton(Icons.Default.Wifi, "WIFI", active = false,
                onClick = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ShutterButton(busy = false) { if (working) onStop() else onConnect("") }
                Spacer(Modifier.height(6.dp))
                Text(if (working) "STOP" else "CONNECT", color = if (working) Color(0xFFB3261E) else EngraveDark,
                    fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
            BodyButton(Icons.Default.Terminal, "LOG", active = false, onClick = onShowLog)
        }
    }
}
