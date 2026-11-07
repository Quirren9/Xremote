package nl.quirren.xremote

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.quirren.xremote.ui.ConnectScreen
import nl.quirren.xremote.ui.LogDialog
import nl.quirren.xremote.ui.RemoteScreen
import nl.quirren.xremote.ui.XRemoteTheme

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            XRemoteTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                var showLog by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    vm.toast.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                }
                LaunchedEffect(state) {
                    if (state == ConnState.Connected) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                if (state == ConnState.Connected) RemoteScreen(vm, onShowLog = { showLog = true })
                else ConnectScreen(state, onConnect = { vm.connect(it) }, onShowLog = { showLog = true })

                if (showLog) LogDialog(vm, onDismiss = { showLog = false })
            }
        }
    }
}
