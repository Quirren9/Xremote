package nl.quirren.xremote

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
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

                LaunchedEffect(Unit) { if (!handleUsb(intent)) vm.autoConnect() }
                LaunchedEffect(Unit) {
                    vm.toast.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                }
                LaunchedEffect(state) {
                    if (state == ConnState.Connected) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                if (state == ConnState.Connected) RemoteScreen(vm, onShowLog = { showLog = true })
                else ConnectScreen(state, onConnect = { vm.connect(it) }, onConnectUsb = { vm.connectUsb() }, onStop = { vm.disconnect() }, onShowLog = { showLog = true })

                if (showLog) LogDialog(vm, onDismiss = { showLog = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsb(intent)
    }

    /** Camera plugged in while the app runs or launched it: connect over the cable. */
    private fun handleUsb(intent: Intent?): Boolean {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        val dev = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        vm.switchToUsb(dev)
        return true
    }
}
