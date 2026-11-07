package nl.quirren.xremote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.quirren.xremote.RemoteViewModel

@Composable
fun LogDialog(vm: RemoteViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val logs by vm.logs.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) list.scrollToItem(logs.size - 1) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(12.dp)) {
            Row {
                Text("Connection log", color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(8.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("X Remote log", vm.logText()))
                }) { Text("Copy") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.logText())
                    ctx.startActivity(Intent.createChooser(send, "Share log"))
                }) { Text("Share") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().background(Panel).padding(8.dp)) {
                items(logs) { Text(it, color = TextHi, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp) }
            }
        }
    }
}
