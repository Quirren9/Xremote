package nl.quirren.xremote

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import nl.quirren.xremote.usb.FujiUsb
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
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import nl.quirren.xremote.ptp.FujiCamera
import nl.quirren.xremote.ptp.FujiProp
import nl.quirren.xremote.ptp.KnownProps
import nl.quirren.xremote.ptp.u16
import nl.quirren.xremote.ptp.u32
import nl.quirren.xremote.ptp.PropDesc
import nl.quirren.xremote.ptp.PropForm
import nl.quirren.xremote.ptp.PropNames
import nl.quirren.xremote.ptp.PropGroup
import nl.quirren.xremote.ptp.PtpLog
import nl.quirren.xremote.ptp.PtpType
import nl.quirren.xremote.ptp.hex16
import nl.quirren.xremote.ptp.hexL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

sealed interface ConnState {
    data object Idle : ConnState
    data class Working(val message: String) : ConnState
    data object Connected : ConnState
    data class Failed(val message: String) : ConnState
}

/** Background saving: photos waiting, and which one of the batch is being copied. */
data class SaveUi(val waiting: Int, val active: Boolean, val index: Int = 0, val total: Int = 0, val frac: Float = 0f, val pausing: Boolean = false)

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
    /** Background save queue, null when nothing is waiting. */
    val saving = MutableStateFlow<SaveUi?>(null)
    /** Shots taken but not yet copied. Kept across reconnects: the photos stay on the card. */
    private val pending = AtomicInteger(0)
    @Volatile private var batchRunning = false
    @Volatile private var yieldReq = false
    @Volatile private var lastActivityNs = 0L
    private val savedNames = HashSet<String>()
    private var saverJob: Job? = null
    private val seenHandles = HashSet<Long>()
    @Volatile private var lastFrameNs = 0L
    private var watchdogJob: Job? = null

    fun toggleAutoSave() { autoSave.value = !autoSave.value; logger.log("Auto-save ${if (autoSave.value) "on" else "off"}"); updateSaveUi() }

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
    @Volatile private var userDisconnected = false
    private var reconnectJob: Job? = null
    private var autoConnectTried = false
    private var lastHost = ""
    private val netFlow = MutableStateFlow<Network?>(null)
    /** 0 = no request, 1 = whatever WiFi the phone is on, 2 = Android's network picker for FUJIFILM-... */
    private var netMode = 0
    @Volatile private var netUnavailable = false

    /** Connect once when the app opens, so a camera that's already on just works. */
    fun autoConnect() {
        if (autoConnectTried) return
        autoConnectTried = true
        if (FujiUsb.findCamera(usbManager) != null) connectUsb() else connect("")
    }

    fun connect(hostOverride: String) {
        if (_state.value is ConnState.Working || _state.value == ConnState.Connected) return
        userDisconnected = false
        usbSession = false
        lastHost = hostOverride
        _state.value = ConnState.Working("Looking for the camera's WiFi")
        viewModelScope.launch {
            try {
                connectOnce(hostOverride, allowPicker = true)
            } catch (e: Exception) {
                logger.log("Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                teardown(releaseNetwork = false)
                _state.value = ConnState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun connectOnce(hostOverride: String, allowPicker: Boolean) {
        _state.value = ConnState.Working("Looking for the camera's WiFi")
        val net = cameraNetwork(hostOverride, allowPicker)
        network = net
        cm.bindProcessToNetwork(net)
        val gateway = gatewayOf(net)
        val host = hostOverride.trim().ifEmpty { gateway ?: "192.168.0.1" }
        logger.log("Using WiFi network $net, camera host $host")

        _state.value = ConnState.Working("Connecting to the camera\nIf the camera asks to accept a new device, press OK on it.")
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
        startSaver(cam)
        updateSaveUi()
    }

    // ---- USB cable (camera in PC CONNECTION MODE > USB TETHER SHOOTING) ----
    private val usbManager = app.getSystemService(UsbManager::class.java)
    @Volatile private var usbSession = false

    fun connectUsb(device: UsbDevice? = null) {
        if (_state.value is ConnState.Working || _state.value == ConnState.Connected) return
        val dev = device ?: FujiUsb.findCamera(usbManager) ?: run {
            _state.value = ConnState.Failed("No Fujifilm camera on the USB cable. Plug it in with the camera switched on.")
            return
        }
        userDisconnected = false
        usbSession = true
        _state.value = ConnState.Working("Connecting over USB")
        viewModelScope.launch {
            try {
                if (!usbManager.hasPermission(dev)) {
                    _state.value = ConnState.Working("Allow X Remote to use the camera in the popup")
                    if (!requestUsbPermission(dev)) throw IllegalStateException("USB access was not allowed")
                }
                val t = withContext(Dispatchers.IO) { FujiUsb.open(usbManager, dev, logger) }
                val cam = FujiCamera(null, "usb", logger, t)
                camera = cam
                withContext(camIo) {
                    cam.connect("")
                    _state.value = ConnState.Working("Reading all camera settings")
                    cam.discover()
                    cam.client.verbose = false
                    publish(cam)
                }
                cameraName.value = cam.deviceInfo?.model ?: dev.productName ?: "Camera"
                cam.startStreams(onFrame = ::onFrame, onEvent = ::onCameraEvent)
                _state.value = ConnState.Connected
                startPolling(cam)
                logger.log("USB session ready, ${cam.descs.size} settings")
            } catch (e: Exception) {
                logger.log("USB connect failed: ${e.javaClass.simpleName}: ${e.message}")
                teardown(releaseNetwork = false)
                _state.value = ConnState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Cable plugged in: drop a WiFi session (or a reconnect attempt) and use the cable. */
    fun switchToUsb(dev: UsbDevice?) {
        if (usbSession && _state.value == ConnState.Connected) return
        userDisconnected = true
        reconnectJob?.cancel(); reconnectJob = null
        viewModelScope.launch {
            if (_state.value != ConnState.Idle && _state.value !is ConnState.Failed) {
                if (batchRunning) { yieldReq = true; withTimeoutOrNull(10_000L) { while (batchRunning) delay(50) } }
                teardown(releaseNetwork = true)
                _state.value = ConnState.Idle
            }
            connectUsb(dev)
        }
    }

    private suspend fun requestUsbPermission(dev: UsbDevice): Boolean = suspendCancellableCoroutine { cont ->
        val ctx = getApplication<Application>()
        val action = "${ctx.packageName}.USB_PERMISSION"
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { ctx.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || usbManager.hasPermission(dev))
            }
        }
        ContextCompat.registerReceiver(ctx, rcv, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        usbManager.requestPermission(dev, pi)
        cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(rcv) } }
    }

    /** USB: a new photo is a plain object; download it right away, live view keeps running. */
    private suspend fun usbSaveObject(cam: FujiCamera, handle: Long) {
        saving.value = SaveUi(waiting = 1, active = true, total = 1)
        try {
            withContext(camIo) {
                val info = cam.objectInfo(handle) ?: run { logger.log("USB object ${hexL(handle)}: no info"); return@withContext }
                logger.log("USB photo ${info.filename} ${info.size / 1024} KB, ${info.width}x${info.height}")
                if (!info.isJpeg) { logger.log("  not a JPEG, left on the camera"); return@withContext }
                val bytes = cam.download(info) { f -> saving.value = SaveUi(1, true, 0, 1, f) } ?: return@withContext
                val opts = BitmapFactory.Options().apply { inSampleSize = if (info.width > 3000) 4 else 1 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let {
                    val img = it.asImageBitmap()
                    _lastShot.value = img
                    reviewHoldUntilNs = System.nanoTime() + 900_000_000L
                    _review.value = img
                }
                if (autoSave.value) {
                    val fresh = synchronized(savedNames) { savedNames.add(info.filename) }
                    if (fresh) {
                        saveToGallery(info.filename, bytes)
                        logger.log("Saved ${info.filename} (${bytes.size / 1024} KB) to Pictures/X Remote")
                    }
                }
            }
        } catch (e: Exception) {
            logger.log("USB download failed: ${e.message}")
        } finally {
            saving.value = null
        }
    }

    /** The camera's network: the WiFi the phone is on if that's the camera, otherwise Android's picker. */
    private suspend fun cameraNetwork(hostOverride: String, allowPicker: Boolean): Network {
        if (netMode == 0) registerNet(picker = false)
        val first = withTimeoutOrNull(if (netMode == 2) 8_000L else 3_000L) { netFlow.filterNotNull().first() }
        if (first != null && (hostOverride.isNotBlank() || isCameraNet(first))) return first
        if (!allowPicker) throw IllegalStateException("Camera WiFi not found")
        if (first != null) logger.log("Phone is on another WiFi (${gatewayOf(first)}), asking Android for the camera's network")
        releaseNet()
        registerNet(picker = true)
        _state.value = ConnState.Working("Pick the camera's FUJIFILM-... network in the window that opens.\nNo window or no network listed? Start wireless communication on the camera first.")
        val picked = withTimeoutOrNull(60_000L) {
            while (netFlow.value == null && !netUnavailable) delay(100)
            netFlow.value
        }
        return picked ?: run {
            releaseNet()
            throw IllegalStateException("Camera WiFi not found. Start wireless communication on the camera, then tap Connect.")
        }
    }

    private fun isCameraNet(n: Network): Boolean = gatewayOf(n).let { it == null || it == "192.168.0.1" }

    private fun registerNet(picker: Boolean) {
        val b = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // camera WiFi has no internet
        if (picker) b.setNetworkSpecifier(
            WifiNetworkSpecifier.Builder()
                .setSsidPattern(PatternMatcher("FUJIFILM", PatternMatcher.PATTERN_PREFIX))
                .build()
        )
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { netFlow.value = n }
            override fun onLost(n: Network) {
                if (netFlow.value == n) netFlow.value = null
                if (n == network && _state.value == ConnState.Connected) lost("Camera WiFi disconnected")
            }
            override fun onUnavailable() { netUnavailable = true; logger.log("No camera network chosen") }
        }
        try {
            cm.requestNetwork(b.build(), cb)
        } catch (e: Exception) {
            logger.log("WiFi request failed: ${e.message}")
            throw IllegalStateException("Android refused the WiFi request. Join the FUJIFILM-... network in WiFi settings, then tap Connect.")
        }
        netCallback = cb
        netUnavailable = false
        netMode = if (picker) 2 else 1
    }

    private fun releaseNet() {
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null; netFlow.value = null; netMode = 0; network = null
        runCatching { cm.bindProcessToNetwork(null) }
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
                            val d = cam.descs[code]
                            if (d != null) cam.descs[code] = d.copy(current = v)
                            else if (code in infoCodes) cam.descs[code] = PropDesc(code, PtpType.UINT32, writable = false, current = v, synthetic = true)
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

    /** Read-only values the camera reports in its snapshot, shown as info tiles (set by the camera's dials). */
    private val infoCodes = setOf(0x500E, 0xD240, 0xD018)

    private val statusCodes = setOf(FujiProp.BATTERY, FujiProp.SHOTS_REMAINING, FujiProp.MOVIE_REMAINING, 0x5001)

    /** If live view stops (it pauses after every shot), restart it. */
    private fun startWatchdog(cam: FujiCamera) {
        watchdogJob = viewModelScope.launch {
            var lastRestart = 0L
            while (isActive) {
                delay(1000)
                val now = System.nanoTime()
                val stalled = lastFrameNs != 0L && now - lastFrameNs > 4_000_000_000L
                if (stalled && !batchRunning && !cam.inGallery && now - lastRestart > 8_000_000_000L) {
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
        if (cam.usb) {
            // 0x4002 = standard ObjectAdded, 0xC004 = Fuji's; either carries the new photo's handle
            if ((event == 0x4002 || event == 0xC004) && params.isNotEmpty() && seenHandles.add(params[0]))
                viewModelScope.launch { usbSaveObject(cam, params[0]) }
            return
        }
        // 0xC004 = ObjectAdded (photo written), 0xC001 = PreviewAvailable (comes ~0.5 s later)
        if (event != 0xC001 || params.isEmpty()) return
        if (!seenHandles.add(params[0])) return
        viewModelScope.launch {
            runCatching { afterShot(cam) }.onFailure { logger.log("After-shot handling failed: ${it.message}") }
        }
    }

    private suspend fun afterShot(cam: FujiCamera) {
        withContext(camIo) {
            cam.capturePreview()?.let { jpeg ->
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let {
                    val img = it.asImageBitmap()
                    _lastShot.value = img
                    reviewHoldUntilNs = System.nanoTime() + 900_000_000L
                    _review.value = img
                }
            }
        }
        // The camera resumes live view by itself after the preview. Copying happens later, in one go,
        // once you pause shooting: switching the camera to its gallery and back costs ~3 s per batch.
        if (autoSave.value) { pending.incrementAndGet(); updateSaveUi() }
    }

    private fun updateSaveUi() {
        if (batchRunning) return
        val n = pending.get()
        saving.value = if (n > 0 && autoSave.value) SaveUi(waiting = n, active = false) else null
    }

    /** Copies waiting photos once nothing has happened for a moment. */
    private fun startSaver(cam: FujiCamera) {
        saverJob?.cancel()
        saverJob = viewModelScope.launch {
            while (isActive) {
                delay(300)
                if (pending.get() <= 0 || !autoSave.value || _busy.value) continue
                if (System.nanoTime() - lastActivityNs < SAVE_IDLE_NS) continue
                runBatch(cam)
            }
        }
    }

    private suspend fun runBatch(cam: FujiCamera) {
        batchRunning = true
        if (System.nanoTime() - lastActivityNs < SAVE_IDLE_NS) { batchRunning = false; return }
        yieldReq = false
        val total = pending.get()
        logger.log("Saving $total photo${if (total == 1) "" else "s"}")
        saving.value = SaveUi(waiting = total, active = true, total = total)
        cam.client.verbose = true
        try {
            withContext(camIo) {
                cam.enterGallery()
                try {
                    val done = cam.saveNewest(total, shouldStop = { yieldReq },
                        progress = { i, f -> saving.value = SaveUi(total - i, true, i, total, f, yieldReq) },
                    ) { name, bytes ->
                        val fresh = synchronized(savedNames) { savedNames.add(name) }
                        if (fresh) {
                            saveToGallery(name, bytes)
                            logger.log("Saved $name (${bytes.size / 1024} KB) to Pictures/X Remote")
                        } else logger.log("$name was already saved")
                    }
                    pending.updateAndGet { maxOf(0, it - done) }
                } finally {
                    cam.enterLiveView()
                }
            }
            if (pending.get() == 0) _toast.tryEmit("Saved $total photo${if (total == 1) "" else "s"}")
        } catch (e: Exception) {
            logger.log("Saving stopped: ${e.message}")
        } finally {
            cam.client.verbose = false
            batchRunning = false
            yieldReq = false
            lastActivityNs = System.nanoTime()
            updateSaveUi()
            logger.log("Ready for the next shot")
        }
    }

    /** Before a shot or a setting change: if a save batch is running, stop it after the current photo. */
    private suspend fun awaitLive(reason: String) {
        lastActivityNs = System.nanoTime()
        if (!batchRunning) return
        yieldReq = true
        logger.log("Pausing saving for $reason")
        saving.value = saving.value?.copy(pausing = true)
        while (batchRunning) delay(40)
        lastActivityNs = System.nanoTime()
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
                name = k?.name ?: KnownProps.nameOf(d.code),
                short = k?.short ?: (PropNames.map[d.code] ?: hex16(d.code)),
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
        if (_review.value != null && !batchRunning && lastFrameNs > reviewHoldUntilNs) _review.value = null
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
            awaitLive("a setting")
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
        _busy.value = true
        lastActivityNs = System.nanoTime()
        viewModelScope.launch {
            awaitLive("a shot")
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
            lastActivityNs = System.nanoTime()
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
            awaitLive("focus")
            logger.log("Focus point -> $x,$y (grid ${maxX + 1}x${maxY + 1})")
            val ok = runCatching { withContext(camIo) { cam.setFocusPoint(x, y) } }.getOrDefault(false)
            if (!ok) _toast.tryEmit("Camera didn't accept the focus point")
        }
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel(); reconnectJob = null
        viewModelScope.launch {
            // Never leave the camera in its gallery: it then refuses the next connection
            if (batchRunning) { yieldReq = true; withTimeoutOrNull(10_000L) { while (batchRunning) delay(50) } }
            teardown(releaseNetwork = true)
            _state.value = ConnState.Idle
        }
    }

    /** Lost the camera: reconnect by itself for a few minutes. Runs in its own coroutine. */
    private fun lost(msg: String) {
        logger.log(msg)
        if (userDisconnected || reconnectJob?.isActive == true) return
        if (usbSession) {
            viewModelScope.launch {
                teardown(releaseNetwork = false)
                _state.value = ConnState.Failed("USB connection lost. Check the cable, then tap Connect over USB.")
            }
            return
        }
        reconnectJob = viewModelScope.launch {
            teardown(releaseNetwork = false)
            val start = System.nanoTime()
            var attempt = 0
            while (isActive && !userDisconnected) {
                attempt++
                _state.value = ConnState.Working("Connection lost, reconnecting (try $attempt)\nKeep the camera on with wireless communication active.")
                try {
                    connectOnce(lastHost, allowPicker = attempt % 6 == 0)
                    logger.log("Reconnected after $attempt tries")
                    return@launch
                } catch (e: Exception) {
                    logger.log("Reconnect $attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                    teardown(releaseNetwork = false)
                }
                if (System.nanoTime() - start > 180_000_000_000L) break
                delay(2500)
            }
            if (!userDisconnected) _state.value = ConnState.Failed("$msg. Tap Connect to try again.")
        }
    }

    private suspend fun teardown(releaseNetwork: Boolean) {
        pollJob?.cancel(); pollJob = null
        watchdogJob?.cancel(); watchdogJob = null
        saverJob?.cancel(); saverJob = null
        lastFrameNs = 0L
        seenHandles.clear()
        batchRunning = false; yieldReq = false
        camera?.let { c -> withContext(Dispatchers.IO) { runCatching { c.close() } } }
        camera = null
        _frame.value = null
        _review.value = null
        _props.value = emptyList()
        _focus.value = null
        _histogram.value = null
        saving.value = null
        if (releaseNetwork) releaseNet()
    }

    private companion object { const val SAVE_IDLE_NS = 6_000_000_000L }

    override fun onCleared() {
        setTorch(false)
        pollJob?.cancel()
        camera?.let { c -> Thread { runCatching { c.close() } }.start() }
        runCatching { cm.bindProcessToNetwork(null) }
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    }
}
