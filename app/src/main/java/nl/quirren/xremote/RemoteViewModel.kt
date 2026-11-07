package nl.quirren.xremote

import android.app.Application
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.quirren.xremote.ptp.FujiCamera
import nl.quirren.xremote.ptp.FujiProp
import nl.quirren.xremote.ptp.KnownProps
import nl.quirren.xremote.ptp.PropDesc
import nl.quirren.xremote.ptp.PropForm
import nl.quirren.xremote.ptp.PropGroup
import nl.quirren.xremote.ptp.PtpLog
import nl.quirren.xremote.ptp.PtpType
import nl.quirren.xremote.ptp.hex16
import nl.quirren.xremote.ptp.hexL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.roundToInt

sealed interface ConnState {
    data object Idle : ConnState
    data class Working(val message: String) : ConnState
    data object Connected : ConnState
    data class Failed(val message: String) : ConnState
}

data class PropUi(
    val code: Int,
    val name: String,
    val short: String,
    val group: PropGroup,
    val order: Int,
    val value: Long,
    val text: String,
    val writable: Boolean,
    val choices: List<Long>,
    val range: PropForm.Range?,
) {
    val raw get() = hexL(value)
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state = _state.asStateFlow()
    private val _props = MutableStateFlow<List<PropUi>>(emptyList())
    val props = _props.asStateFlow()
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame = _frame.asStateFlow()
    private val _fps = MutableStateFlow(0f)
    val fps = _fps.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _focus = MutableStateFlow<Pair<Float, Float>?>(null)
    val focus = _focus.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()
    val cameraName = MutableStateFlow("")

    /** All camera I/O goes through one thread so transactions never interleave. */
    private val camIo = Dispatchers.IO.limitedParallelism(1)
    private var camera: FujiCamera? = null
    private var pollJob: Job? = null
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null

    // ---- logging ----
    private val logBuf = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logDirty = AtomicBoolean(false)
    val logger = PtpLog { msg ->
        Log.d("XRemote", msg)
        synchronized(logBuf) {
            logBuf.addLast("${timeFmt.format(Date())}  $msg")
            while (logBuf.size > 3000) logBuf.removeFirst()
        }
        logDirty.set(true)
    }

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(300)
                if (logDirty.getAndSet(false)) _logs.value = synchronized(logBuf) { logBuf.toList() }
            }
        }
    }

    fun logText(): String = synchronized(logBuf) { logBuf.joinToString("\n") }

    // ---- connection ----
    fun connect(hostOverride: String) {
        if (_state.value is ConnState.Working || _state.value == ConnState.Connected) return
        viewModelScope.launch {
            try {
                _state.value = ConnState.Working("Looking for the camera's WiFi network")
                val net = try {
                    withTimeout(10_000) { requestWifi() }
                } catch (e: Exception) {
                    throw IllegalStateException("No WiFi network found. Connect the phone to the camera's FUJIFILM-... network first.")
                }
                network = net
                cm.bindProcessToNetwork(net)
                val gateway = gatewayOf(net)
                if (hostOverride.isBlank() && gateway != null && gateway != "192.168.0.1") {
                    throw IllegalStateException("The phone is on another WiFi network ($gateway). Connect to the camera's FUJIFILM-... network, and turn off auto-reconnect for your home WiFi if Android keeps switching back.")
                }
                val host = hostOverride.trim().ifEmpty { gateway ?: "192.168.0.1" }
                logger.log("Using WiFi network $net, camera host $host")

                _state.value = ConnState.Working("Connecting to $host\nIf the camera asks to accept a new device, press OK on it.")
                val cam = FujiCamera(net.socketFactory, host, logger)
                camera = cam
                withContext(camIo) {
                    cam.connect("X Remote ${Build.MODEL}".take(26))
                    _state.value = ConnState.Working("Reading camera settings")
                    cam.discover()
                    cam.client.verbose = false
                    publish(cam)
                }
                cameraName.value = cam.deviceInfo?.model ?: cam.client.cameraName ?: "Camera"
                withContext(camIo) { cam.startRemote() }
                cam.startStreams(onFrame = ::onFrame)
                _state.value = ConnState.Connected
                startPolling(cam)
            } catch (e: Exception) {
                logger.log("Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                teardown()
                _state.value = ConnState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun requestWifi(): Network = suspendCancellableCoroutine { cont ->
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // camera WiFi has no internet
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { if (cont.isActive) cont.resume(n) }
            override fun onLost(n: Network) {
                if (n == network && _state.value == ConnState.Connected) {
                    lost("Camera WiFi disconnected")
                }
            }
        }
        netCallback = cb
        cm.requestNetwork(req, cb)
        cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) }; netCallback = null }
    }

    private fun gatewayOf(net: Network): String? {
        val lp = cm.getLinkProperties(net) ?: return null
        if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress?.let { return it }
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
    }

    private fun startPolling(cam: FujiCamera) {
        pollJob = viewModelScope.launch {
            var tick = 0
            var failures = 0
            while (isActive) {
                delay(400)
                try {
                    withContext(camIo) {
                        val changes = cam.pollChanges()
                        for ((code, v) in changes) {
                            if (code in FujiProp.internal) continue
                            val d = cam.descs[code]
                            cam.descs[code] = d?.copy(current = v) ?: PropDesc(code, PtpType.UINT32, true, v, synthetic = true)
                        }
                        // Full refresh every ~3 s, in case the change list isn't supported.
                        if (tick % 8 == 0) for (code in cam.descs.keys.toList()) {
                            cam.readValue(code)?.let { cam.descs[code] = cam.descs[code]!!.copy(current = it) }
                        }
                        if (changes.isNotEmpty() || tick % 8 == 0) publish(cam)
                    }
                    failures = 0
                } catch (e: Exception) {
                    failures++
                    logger.log("Poll error: ${e.message}")
                    if (failures >= 3) { lost("Lost connection to camera"); return@launch }
                }
                tick++
            }
        }
    }

    private fun publish(cam: FujiCamera) {
        _props.value = cam.descs.values.map { d ->
            val k = KnownProps.get(d.code)
            PropUi(
                code = d.code,
                name = k?.name ?: "Property ${hex16(d.code)}",
                short = k?.short ?: hex16(d.code),
                group = k?.group ?: PropGroup.OTHER,
                order = k?.order ?: (1000 + d.code),
                value = d.current,
                text = KnownProps.format(d.code, d.current, d.text),
                writable = d.writable,
                choices = d.choices(),
                range = d.form as? PropForm.Range,
            )
        }.sortedBy { it.order }
    }

    // ---- live view ----
    private val decoding = AtomicBoolean(false)
    private var fpsCount = 0
    private var fpsStart = 0L
    private var framesSeen = 0L

    private fun onFrame(jpeg: ByteArray) {
        if (framesSeen++ == 0L) logger.log("First live view frame (${jpeg.size} bytes)")
        if (!decoding.compareAndSet(false, true)) return // drop frames while busy
        try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            _frame.value = bmp.asImageBitmap()
            val now = System.nanoTime()
            if (fpsStart == 0L) fpsStart = now
            fpsCount++
            if (now - fpsStart > 1_000_000_000L) {
                _fps.value = fpsCount * 1e9f / (now - fpsStart)
                fpsCount = 0; fpsStart = now
            }
        } finally { decoding.set(false) }
    }

    // ---- controls ----
    fun setProp(code: Int, value: Long) {
        val cam = camera ?: return
        viewModelScope.launch {
            val name = KnownProps.nameOf(code)
            logger.log("Set $name -> ${KnownProps.format(code, value)} (${hexL(value)})")
            val ok = runCatching { withContext(camIo) {
                val ok = cam.setValue(code, value)
                cam.readValue(code)?.let { v -> cam.descs[code]?.let { cam.descs[code] = it.copy(current = v) } }
                publish(cam)
                ok
            } }.getOrElse { logger.log("Set failed: ${it.message}"); false }
            if (!ok) _toast.tryEmit("Camera refused $name. Check that its dial or ring is on A.")
        }
    }

    fun shutter() {
        val cam = camera ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            logger.log("Shutter")
            val ok = runCatching { withContext(camIo) { cam.shutter() } }.getOrElse { logger.log("Shutter error: ${it.message}"); false }
            if (!ok) _toast.tryEmit("Shot failed, see log")
            _busy.value = false
        }
    }

    /** nx, ny in 0..1 over the displayed live view image. */
    fun tapFocus(nx: Float, ny: Float) {
        val cam = camera ?: return
        val range = cam.descs[FujiProp.FOCUS_POINT]?.form as? PropForm.Range
        val maxX = range?.let { ((it.max ushr 8) and 0xFF).toInt() }?.takeIf { it > 0 } ?: 12
        val maxY = range?.let { (it.max and 0xFF).toInt() }?.takeIf { it > 0 } ?: 8
        val x = (nx * maxX).roundToInt().coerceIn(0, maxX)
        val y = (ny * maxY).roundToInt().coerceIn(0, maxY)
        _focus.value = x.toFloat() / maxX to y.toFloat() / maxY
        viewModelScope.launch {
            logger.log("Focus point -> $x,$y (grid ${maxX + 1}x${maxY + 1})")
            val ok = runCatching { withContext(camIo) { cam.setFocusPoint(x, y) } }.getOrDefault(false)
            if (!ok) _toast.tryEmit("Camera didn't accept the focus point")
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            teardown()
            _state.value = ConnState.Idle
        }
    }

    /** Runs in its own coroutine so it also works when called from the poll job it cancels. */
    private fun lost(msg: String) {
        logger.log(msg)
        viewModelScope.launch {
            teardown()
            _state.value = ConnState.Failed(msg)
        }
    }

    private suspend fun teardown() {
        pollJob?.cancel(); pollJob = null
        camera?.let { c -> withContext(Dispatchers.IO) { runCatching { c.close() } } }
        camera = null
        _frame.value = null
        _props.value = emptyList()
        _focus.value = null
        runCatching { cm.bindProcessToNetwork(null) }
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null; network = null
    }

    override fun onCleared() {
        pollJob?.cancel()
        camera?.let { c -> Thread { runCatching { c.close() } }.start() }
        runCatching { cm.bindProcessToNetwork(null) }
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    }
}
