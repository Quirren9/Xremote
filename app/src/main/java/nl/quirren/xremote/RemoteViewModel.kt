package nl.quirren.xremote

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import nl.quirren.xremote.ptp.u16
import nl.quirren.xremote.ptp.u32
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
    /** Phone torch lights up for shots fired from the app. */
    val torch = MutableStateFlow(false)
    private val _histogram = MutableStateFlow<FloatArray?>(null)
    /** 64-bin luminance histogram of the live view, normalised to the tallest bin. */
    val histogram = _histogram.asStateFlow()
    /** Save every JPEG the camera takes to Pictures/X Remote. */
    val autoSave = MutableStateFlow(true)
    private val _lastShot = MutableStateFlow<ImageBitmap?>(null)
    /** Preview of the most recent shot, shown as a thumbnail. */
    val lastShot = _lastShot.asStateFlow()
    /** The shot just taken, shown full size in the viewer until live view frames come back. */
    private val _review = MutableStateFlow<ImageBitmap?>(null)
    val review = _review.asStateFlow()
    @Volatile private var reviewHoldUntilNs = 0L
    /** Short transfer status for the status bar, null when idle. */
    val transfer = MutableStateFlow<String?>(null)
    private val seenHandles = HashSet<Long>()
    @Volatile private var lastFrameNs = 0L
    private var watchdogJob: Job? = null

    fun toggleAutoSave() { autoSave.value = !autoSave.value; logger.log("Auto-save ${if (autoSave.value) "on" else "off"}") }

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
                withContext(camIo) {
                    if (!cam.startRemote()) {
                        // Usually a session left open by an app that was killed (e.g. reinstalled) mid-session
                        logger.log("Camera didn't start live view, running the full live view setup once")
                        _state.value = ConnState.Working("Waking up the camera's live view")
                        cam.client.verbose = true
                        cam.endLiveView()
                        cam.enterLiveView()
                        cam.client.verbose = false
                    }
                }
                cam.startStreams(onFrame = ::onFrame, onEvent = ::onCameraEvent)
                _state.value = ConnState.Connected
                startPolling(cam)
                startWatchdog(cam)
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
                        // The change list's format isn't confirmed on the X-T3, so it only updates status values;
                        // everything else comes from the direct reads below.
                        val changes = cam.pollChanges()
                        // D212 = snapshot of (code, value) pairs, decoding confirmed on the X-T3
                        if (cam.inGallery) return@withContext   // gallery-mode snapshots mean something else
                        for ((code, v) in changes) {
                            if (code in FujiProp.internal) continue
                            cam.descs[code]?.let { cam.descs[code] = it.copy(current = v) }
                        }
                        // Direct reads only when the camera sends no snapshot
                        if (changes.isEmpty() && tick % 5 == 0) for (code in cam.descs.keys.toList()) {
                            cam.readValue(code)?.let { cam.descs[code] = cam.descs[code]!!.copy(current = it) }
                        }
                        publish(cam)
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

    private val statusCodes = setOf(FujiProp.BATTERY, FujiProp.SHOTS_REMAINING, FujiProp.MOVIE_REMAINING, 0x5001)

    /** If live view stops (it pauses after every shot), restart it. */
    private fun startWatchdog(cam: FujiCamera) {
        watchdogJob = viewModelScope.launch {
            var lastRestart = 0L
            while (isActive) {
                delay(1000)
                val now = System.nanoTime()
                val stalled = lastFrameNs != 0L && now - lastFrameNs > 4_000_000_000L
                if (stalled && transfer.value == null && !cam.inGallery && now - lastRestart > 8_000_000_000L) {
                    lastRestart = now
                    logger.log("Live view stalled, switching camera back to live view")
                    runCatching { withContext(camIo) { cam.enterLiveView() } }
                }
            }
        }
    }

    // ---- photos to phone ----
    private fun onCameraEvent(code: Int, body: ByteArray) {
        // body: u16 type, u16 event code, u32 transaction, then u32 params
        if (body.size < 12) return
        val event = body.u16(2)
        val params = (8 until body.size - 3 step 4).map { body.u32(it) }
        logger.log("Camera event ${hex16(event)} params ${params.joinToString { hexL(it) }}")
        val cam = camera ?: return
        // 0xC004 = ObjectAdded (photo written), 0xC001 = PreviewAvailable (comes ~0.5 s later)
        if (event != 0xC001 || params.isEmpty()) return
        if (!seenHandles.add(params[0])) return
        viewModelScope.launch {
            runCatching { afterShot(cam) }.onFailure { logger.log("After-shot handling failed: ${it.message}") }
        }
    }

    private suspend fun afterShot(cam: FujiCamera) {
        transfer.value = if (autoSave.value) "Preview" else null
        cam.client.verbose = autoSave.value
        var switched = false
        try {
            if (!autoSave.value) transfer.value = null   // never block the shutter for a preview
            withContext(camIo) {
                cam.capturePreview()?.let { jpeg ->
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let {
                        val img = it.asImageBitmap()
                        _lastShot.value = img
                        // Without saving, show it briefly; with saving, until live view is back.
                        reviewHoldUntilNs = System.nanoTime() + if (autoSave.value) 0L else 1_200_000_000L
                        _review.value = img
                    }
                }
            }
            if (autoSave.value) {
                transfer.value = "Saving"
                switched = true
                val shot = withContext(camIo) {
                    cam.enterGallery()
                    cam.fetchNewestJpeg { f -> transfer.value = "Saving ${(f * 100).toInt()}%" }
                }
                if (shot == null) {
                    logger.log("Could not copy the photo (see the lines above)")
                    _toast.tryEmit("Couldn't copy the photo, the log shows why")
                } else {
                    val (name, bytes, method) = shot
                    withContext(Dispatchers.IO) { saveToGallery(name, bytes) }
                    logger.log("Saved $name (${bytes.size / 1024} KB, $method) to Pictures/X Remote")
                    _toast.tryEmit("Saved $name")
                }
                transfer.value = "Back to live view"
                runCatching { withContext(camIo) { cam.enterLiveView() } }.onFailure { logger.log("Back to live view failed: ${it.message}") }
            }
            // Without a download the camera resumes live view by itself after the preview;
            // the watchdog switches back explicitly only if frames don't return.
        } finally {
            transfer.value = null
            cam.client.verbose = false
            if (switched) logger.log("Ready for the next shot")
        }
    }

    private fun saveToGallery(name: String, bytes: ByteArray) {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/X Remote")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Can't open output")
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
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
        lastFrameNs = System.nanoTime()
        if (_review.value != null && transfer.value == null && lastFrameNs > reviewHoldUntilNs) _review.value = null
        if (!decoding.compareAndSet(false, true)) return // drop frames while busy
        try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            _frame.value = bmp.asImageBitmap()
            if (framesSeen % 3 == 0L) _histogram.value = luminanceHistogram(bmp)
            val now = System.nanoTime()
            if (fpsStart == 0L) fpsStart = now
            fpsCount++
            if (now - fpsStart > 1_000_000_000L) {
                _fps.value = fpsCount * 1e9f / (now - fpsStart)
                fpsCount = 0; fpsStart = now
            }
        } finally { decoding.set(false) }
    }

    private fun luminanceHistogram(b: Bitmap): FloatArray {
        val w = 96; val h = 64
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val bins = FloatArray(64)
        for (p in px) {
            val y = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)).toInt()
            bins[(y * 64 / 256).coerceIn(0, 63)]++
        }
        val max = bins.max().coerceAtLeast(1f)
        for (i in bins.indices) bins[i] /= max
        return bins
    }

    // ---- phone torch as a fill light ----
    private val cameraManager = app.getSystemService(CameraManager::class.java)
    private val torchId: String? by lazy {
        runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                val c = cameraManager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull()
    }

    fun toggleTorch() {
        if (torchId == null) { _toast.tryEmit("This phone has no usable flash"); return }
        torch.value = !torch.value
        logger.log("Phone light ${if (torch.value) "on" else "off"} for shots")
    }

    private fun setTorch(on: Boolean) {
        val id = torchId ?: return
        runCatching {
            if (on && Build.VERSION.SDK_INT >= 33) {
                val max = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                if (max > 1) cameraManager.turnOnTorchWithStrengthLevel(id, max) else cameraManager.setTorchMode(id, true)
            } else cameraManager.setTorchMode(id, on)
        }.onFailure { logger.log("Torch: ${it.message}") }
    }

    // ---- controls ----
    fun setProp(code: Int, value: Long) {
        val cam = camera ?: return
        viewModelScope.launch {
            val name = KnownProps.nameOf(code)
            logger.log("Set $name -> ${KnownProps.format(code, value)} (${hexL(value)})")
            val ok = runCatching { withContext(camIo) {
                val ok = cam.setValue(code, value)
                val back = cam.readValue(code)
                logger.log("  -> ${if (ok) "accepted" else "refused"}, camera now reports ${back?.let { KnownProps.format(code, it) + " (" + hexL(it) + ")" } ?: "nothing"}")
                back?.let { v -> cam.descs[code]?.let { cam.descs[code] = it.copy(current = v) } }
                publish(cam)
                ok
            } }.getOrElse { logger.log("Set failed: ${it.message}"); false }
            // Read once more a second later: catches the camera reverting a value on its own
            delay(1200)
            runCatching { withContext(camIo) {
                val later = cam.readValue(code)
                if (later != null && later != value) logger.log("  ! ${KnownProps.nameOf(code)} is ${KnownProps.format(code, later)} (${hexL(later)}) 1.2 s later")
            } }
            if (!ok) _toast.tryEmit("Camera refused $name. Check that its dial or ring is on A.")
        }
    }

    fun shutter() {
        val cam = camera ?: return
        if (_busy.value) return
        if (transfer.value != null) { _toast.tryEmit("Wait a moment: ${transfer.value}"); return }
        _busy.value = true
        viewModelScope.launch {
            logger.log("Shutter")
            val light = torch.value
            if (light) { setTorch(true); delay(250) }   // LED to full brightness and the camera meters with it
            val ok = try {
                runCatching { withContext(camIo) { cam.shutter() } }.getOrElse { logger.log("Shutter error: ${it.message}"); false }
            } finally {
                if (light) {
                    // Keep the light on for the whole exposure: shutter speed (0xD240, microseconds) + margin
                    val us = cam.descs[0xD240]?.current?.let { it and 0x0FFFFFFF } ?: 0L
                    delay((us / 1000 + 200).coerceIn(200, 30_000))
                    setTorch(false)
                }
            }
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
        watchdogJob?.cancel(); watchdogJob = null
        lastFrameNs = 0L
        seenHandles.clear()
        transfer.value = null
        camera?.let { c -> withContext(Dispatchers.IO) { runCatching { c.close() } } }
        camera = null
        _frame.value = null
        _review.value = null
        _props.value = emptyList()
        _focus.value = null
        _histogram.value = null
        runCatching { cm.bindProcessToNetwork(null) }
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null; network = null
    }

    override fun onCleared() {
        setTorch(false)
        pollJob?.cancel()
        camera?.let { c -> Thread { runCatching { c.close() } }.start() }
        runCatching { cm.bindProcessToNetwork(null) }
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    }
}
