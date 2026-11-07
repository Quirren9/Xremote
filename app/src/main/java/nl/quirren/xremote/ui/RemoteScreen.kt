package nl.quirren.xremote.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownloadOff
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import nl.quirren.xremote.PropUi
import nl.quirren.xremote.RemoteViewModel
import nl.quirren.xremote.SaveUi
import nl.quirren.xremote.ptp.FujiProp
import nl.quirren.xremote.ptp.KnownProps
import nl.quirren.xremote.ptp.PropGroup
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ---- Silver body palette: brushed aluminium top plate, black leatherette, white engraving ----
private val SilverHi = Color(0xFFF1F1EE)
private val Silver = Color(0xFFCFD0CC)
private val SilverMid = Color(0xFFAEAFAB)
private val SilverLo = Color(0xFF858683)
private val Leather = Color(0xFF141414)
private val Engrave = Color(0xFFF3F3EF)
private val EngraveDark = Color(0xFF2B2B2B)
private val Lens = Color(0xFF070707)
private const val FILM_SIM = 0xD001

private val evfText = TextStyle(color = Engrave, fontSize = 12.sp, fontWeight = FontWeight.Medium,
    letterSpacing = 0.6.sp, shadow = Shadow(Color.Black, Offset(0f, 1f), 3f))

@Composable
fun RemoteScreen(vm: RemoteViewModel, onShowLog: () -> Unit) {
    val props by vm.props.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val fps by vm.fps.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val name by vm.cameraName.collectAsStateWithLifecycle()
    val autoSave by vm.autoSave.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val torch by vm.torch.collectAsStateWithLifecycle()
    val histogram by vm.histogram.collectAsStateWithLifecycle()
    val film = props.find { it.code == FILM_SIM }

    Row(Modifier.fillMaxSize().background(Lens)) {
        FilmDial(film, onPick = { vm.setProp(FILM_SIM, it) }, modifier = Modifier.width(138.dp).fillMaxHeight())

        // Live view: as large as the screen allows, everything else floats on top like the EVF
        Box(Modifier.weight(1f).fillMaxHeight().background(Lens)) {
            LiveView(frame, focus, onTap = vm::tapFocus, modifier = Modifier.fillMaxSize())
            review?.let { shot ->
                Image(bitmap = shot, contentDescription = "Last shot", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().background(Lens))
            }
            if (saving?.active == true && review == null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Text("LIVE VIEW PAUSED WHILE SAVING\nPress the shutter to shoot right away",
                        style = evfText.copy(fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp))
                }
            }
            EvfInfo(name, props, film, fps, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
            if (review == null) histogram?.let { Histogram(it, Modifier.align(Alignment.BottomStart).padding(12.dp)) }
            saving?.let { SaveBadge(it, Modifier.align(Alignment.BottomEnd).padding(12.dp)) }
        }

        TopPlateRail(
            busy = busy, autoSave = autoSave, torch = torch,
            onShutter = vm::shutter, onLog = onShowLog, onToggleSave = vm::toggleAutoSave,
            onToggleTorch = vm::toggleTorch, onDisconnect = vm::disconnect,
            modifier = Modifier.width(118.dp).fillMaxHeight(),
        )
    }
}

// ---------------------------------------------------------------- film simulation dial

/** Swatch per film simulation, a quick visual hint of its character. */
private fun filmColor(v: Long): Color = when (v) {
    1L -> Color(0xFF5B9BD5); 2L -> Color(0xFFD9452B); 3L -> Color(0xFFE6A3B4)
    4L -> Color(0xFFD7A77A); 5L -> Color(0xFFC8B89D)
    6L, 7L, 8L, 9L -> Color(0xFF9A9A9A); 10L -> Color(0xFFA2784A)
    11L -> Color(0xFF7D8A77); 12L, 13L, 14L, 15L -> Color(0xFF4A4A4A)
    16L -> Color(0xFF5E7E82); else -> Color(0xFF777777)
}

/**
 * Vertical drum, like the camera's own dials: scroll or fling it, the sim under the index mark is
 * applied when it settles. Tapping a name turns the drum to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilmDial(prop: PropUi?, onPick: (Long) -> Unit, modifier: Modifier) {
    Column(modifier.leatherette().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("FILM", color = Engrave, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text("SIMULATION", color = Engrave.copy(alpha = 0.7f), fontSize = 8.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        val choices = prop?.choices.orEmpty()
        if (prop == null || choices.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(prop?.text ?: "-", color = Engrave, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            return@Column
        }
        val state = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val itemH = 40.dp
        val itemPx = with(LocalDensity.current) { itemH.toPx() }
        val fling = rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Center)
        val center by remember {
            derivedStateOf {
                val li = state.layoutInfo
                val mid = (li.viewportStartOffset + li.viewportEndOffset) / 2
                li.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: 0
            }
        }
        // Follow the camera (its own controls, or the value after a set) when nobody is turning the drum
        LaunchedEffect(prop.value, choices) {
            val i = choices.indexOf(prop.value)
            if (i >= 0 && !state.isScrollInProgress && i != center) state.scrollToItem(i)
        }
        LaunchedEffect(choices) {
            snapshotFlow { state.isScrollInProgress }.drop(1).collect { moving ->
                if (!moving) choices.getOrNull(center)?.let { v -> if (v != prop.value) onPick(v) }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            val pad = ((maxHeight - itemH) / 2).coerceAtLeast(0.dp)
            // The drum itself: a silver cylinder, darker towards the top and bottom edge
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                    .background(Brush.verticalGradient(listOf(SilverLo, Silver, SilverHi, Silver, SilverLo)))
                    .drawBehind { knurl(this.size.width, this.size.height, itemPx) },
            )
            LazyColumn(
                state = state, flingBehavior = fling,
                contentPadding = PaddingValues(vertical = pad),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(choices) { i, v ->
                    val sel = i == center
                    Row(
                        Modifier.height(itemH).fillMaxWidth()
                            .pointerInput(i) { detectTapGestures { scope.launch { state.animateScrollToItem(i) } } }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(if (sel) 8.dp else 6.dp).clip(CircleShape).background(filmColor(v)))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            KnownProps.format(FILM_SIM, v).uppercase(Locale.US),
                            color = EngraveDark.copy(alpha = if (sel) 1f else 0.55f),
                            fontSize = if (sel) 12.sp else 10.sp,
                            fontWeight = if (sel) FontWeight.Black else FontWeight.SemiBold,
                            letterSpacing = 0.5.sp, maxLines = 1,
                        )
                    }
                }
            }
            // Index mark, like the white line next to each dial on the top plate
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2
                val tri = Path().apply {
                    moveTo(-2f, cy - 7.dp.toPx()); lineTo(6.dp.toPx(), cy); lineTo(-2f, cy + 7.dp.toPx()); close()
                }
                drawPath(tri, Engrave)
                drawLine(EngraveDark.copy(alpha = 0.35f), Offset(10.dp.toPx(), cy - itemPx / 2), Offset(size.width - 4.dp.toPx(), cy - itemPx / 2), 1f)
                drawLine(EngraveDark.copy(alpha = 0.35f), Offset(10.dp.toPx(), cy + itemPx / 2), Offset(size.width - 4.dp.toPx(), cy + itemPx / 2), 1f)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("TURN TO CHANGE", color = Engrave.copy(alpha = 0.45f), fontSize = 7.sp, letterSpacing = 1.5.sp)
    }
}

/** Fine ribs at the drum's ends, the knurling on the camera's dials. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.knurl(w: Float, h: Float, itemPx: Float) {
    val band = minOf(h * 0.18f, itemPx * 1.4f)
    var y = 3f
    while (y < band) {
        drawLine(Color.Black.copy(alpha = 0.18f), Offset(0f, y), Offset(w, y), 1.2f)
        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, y + 1.2f), Offset(w, y + 1.2f), 1f)
        drawLine(Color.Black.copy(alpha = 0.18f), Offset(0f, h - y), Offset(w, h - y), 1.2f)
        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, h - y - 1.2f), Offset(w, h - y - 1.2f), 1f)
        y += 4.5f
    }
}

/** Black leatherette with a subtle pebble grain. */
private fun Modifier.leatherette(): Modifier = this.background(Leather).drawBehind {
    val rnd = Random(7)
    repeat((size.width * size.height / 90f).toInt().coerceAtMost(9000)) {
        val x = rnd.nextFloat() * size.width
        val y = rnd.nextFloat() * size.height
        val light = rnd.nextBoolean()
        drawCircle(if (light) Color.White.copy(alpha = 0.035f) else Color.Black.copy(alpha = 0.5f), radius = 1.1f + rnd.nextFloat(), center = Offset(x, y))
    }
}

// ---------------------------------------------------------------- top plate rail (shutter side)

/** Brushed aluminium: fine horizontal grain over a soft vertical sheen. */
private fun Modifier.brushedSilver(): Modifier = this
    .background(Brush.horizontalGradient(listOf(SilverMid, SilverHi, Silver, SilverMid)))
    .drawBehind {
        val rnd = Random(3)
        var y = 0f
        while (y < size.height) {
            val a = 0.03f + rnd.nextFloat() * 0.05f
            drawLine(if (rnd.nextBoolean()) Color.White.copy(alpha = a) else Color.Black.copy(alpha = a * 0.6f), Offset(0f, y), Offset(size.width, y), 1f)
            y += 1.6f + rnd.nextFloat() * 1.6f
        }
        // edge bevels
        drawLine(Color.White.copy(alpha = 0.7f), Offset(1f, 0f), Offset(1f, size.height), 2f)
        drawLine(Color.Black.copy(alpha = 0.35f), Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 2f)
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopPlateRail(
    busy: Boolean, autoSave: Boolean, torch: Boolean,
    onShutter: () -> Unit, onLog: () -> Unit, onToggleSave: () -> Unit, onToggleTorch: () -> Unit, onDisconnect: () -> Unit,
    modifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    Column(
        modifier.brushedSilver().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BodyButton(if (torch) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff, "LIGHT", active = torch, onClick = onToggleTorch)
            BodyButton(if (autoSave) Icons.Default.Download else Icons.Default.FileDownloadOff, "SAVE", active = autoSave, onClick = onToggleSave)
        }
        ShutterButton(busy) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onShutter() }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BodyButton(Icons.Default.Terminal, "LOG", active = false, onClick = onLog)
            BodyButton(
                Icons.Default.LinkOff, "HOLD OFF", active = false, tint = Color(0xFFE5534B),
                onClick = { Toast.makeText(ctx, "Hold to disconnect", Toast.LENGTH_SHORT).show() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDisconnect() },
            )
        }
    }
}

/** Small black button with an engraved label under it, like Q and AE-L on the body. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BodyButton(
    icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null, tint: Color = Engrave,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF3A3A3A), Color(0xFF0E0E0E))))
                .border(if (active) 2.dp else 1.dp, if (active) Accent else Color.Black.copy(alpha = 0.6f), CircleShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (active) Accent else tint, modifier = Modifier.size(19.dp)) }
        Text(label, color = EngraveDark, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            modifier = Modifier.padding(top = 2.dp))
    }
}

/** Silver shutter release: knurled collar, domed button, threaded cable-release socket in the middle. */
@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.93f else 1f, label = "press")
    Box(contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.size(92.dp).scale(s)
                .clip(CircleShape)
                .combinedClickable(interactionSource = src, indication = null, enabled = !busy, onClick = onClick),
        ) {
            val r = size.minDimension / 2
            val c = center
            // knurled collar
            drawCircle(Brush.radialGradient(listOf(Color(0xFF5A5B58), Color(0xFF2A2A29)), c, r), r, c)
            val ticks = 72
            for (i in 0 until ticks) {
                val a = 2 * PI * i / ticks
                val o = Offset(c.x + cos(a).toFloat() * r * 0.97f, c.y + sin(a).toFloat() * r * 0.97f)
                val n = Offset(c.x + cos(a).toFloat() * r * 0.84f, c.y + sin(a).toFloat() * r * 0.84f)
                drawLine(if (i % 2 == 0) Color(0xFFBFC0BC) else Color(0xFF3B3B3A), n, o, 2.2f)
            }
            // domed silver button
            val dome = r * 0.80f
            drawCircle(
                Brush.radialGradient(
                    listOf(if (pressed) Silver else SilverHi, Silver, SilverMid, SilverLo),
                    center = Offset(c.x - dome * 0.3f, c.y - dome * 0.35f), radius = dome * 1.5f,
                ), dome, c,
            )
            drawCircle(Color.Black.copy(alpha = 0.25f), dome, c, style = Stroke(1.5f))
            // threaded socket
            val sock = dome * 0.30f
            drawCircle(Color(0xFF1A1A1A), sock, c)
            for (k in 1..3) drawCircle(Color(0xFF555555), sock * (0.35f + k * 0.18f), c, style = Stroke(1f))
            drawCircle(Color.White.copy(alpha = 0.5f), sock, c, style = Stroke(1f))
        }
        if (busy) CircularProgressIndicator(Modifier.size(100.dp), color = Accent, strokeWidth = 3.dp)
    }
}

// ---------------------------------------------------------------- live view overlays

/** EVF-style info line: plain white text with a shadow, no boxes. */
@Composable
private fun EvfInfo(name: String, props: List<PropUi>, film: PropUi?, fps: Float?, modifier: Modifier) {
    val status = props.filter { it.group == PropGroup.STATUS }
    val shutter = props.find { it.code == 0xD240 }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name.uppercase(Locale.US), style = evfText.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp))
        film?.let { Text(it.text.uppercase(Locale.US), style = evfText.copy(color = Accent, fontWeight = FontWeight.Bold)) }
        shutter?.let { Text(it.text, style = evfText) }
        status.forEach { Text("${it.short.uppercase(Locale.US)} ${it.text}", style = evfText) }
        if (fps != null && fps > 0) Text(String.format(Locale.US, "%.0f FPS", fps), style = evfText.copy(color = Engrave.copy(alpha = 0.6f)))
    }
}

/** Luminance histogram, shadows left, highlights right; clipped ends turn red. */
@Composable
private fun Histogram(bins: FloatArray, modifier: Modifier) {
    val clipLow = bins.first() > 0.6f
    val clipHigh = bins.last() > 0.6f
    Canvas(modifier.size(width = 150.dp, height = 64.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.45f))) {
        val w = size.width; val h = size.height; val pad = 5.dp.toPx()
        val path = Path().apply {
            moveTo(pad, h - pad)
            bins.forEachIndexed { i, v -> lineTo(pad + i * (w - 2 * pad) / (bins.size - 1), h - pad - v * (h - 2 * pad)) }
            lineTo(w - pad, h - pad); close()
        }
        drawPath(path, Engrave.copy(alpha = 0.8f), style = Fill)
        for (q in 1..3) drawLine(Color.White.copy(alpha = 0.15f), Offset(w * q / 4, pad), Offset(w * q / 4, h - pad), 1f)
        if (clipLow) drawRect(Danger, topLeft = Offset(0f, 0f), size = Size(3.dp.toPx(), h))
        if (clipHigh) drawRect(Danger, topLeft = Offset(w - 3.dp.toPx(), 0f), size = Size(3.dp.toPx(), h))
    }
}

/** Corner card: how many shots wait to be copied, and progress while copying. */
@Composable
private fun SaveBadge(s: SaveUi, modifier: Modifier) {
    val label = when {
        s.pausing -> "PAUSING SAVE"
        s.active -> "SAVING ${s.index + 1}/${s.total}"
        else -> "${s.waiting} TO SAVE"
    }
    Column(modifier.width(150.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 9.dp, vertical = 6.dp)) {
        Text(label, style = evfText.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
        if (!s.active && !s.pausing) Text("after 6 s without shooting", style = evfText.copy(fontSize = 9.sp, color = Engrave.copy(alpha = 0.6f)))
        val p = if (s.active && s.total > 0) ((s.index + s.frac) / s.total).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
            color = Accent, trackColor = Color.White.copy(alpha = 0.2f))
    }
}

@Composable
private fun LiveView(frame: ImageBitmap?, focus: Pair<Float, Float>?, onTap: (Float, Float) -> Unit, modifier: Modifier) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.background(Lens).onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
        if (frame == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Engrave, strokeWidth = 2.dp)
                Spacer(Modifier.height(10.dp))
                Text("WAITING FOR LIVE VIEW", style = evfText)
            }
            return@Box
        }
        // Rect the image occupies inside the box with ContentScale.Fit
        val imgAspect = frame.width.toFloat() / frame.height
        val boxAspect = if (box.height == 0) 1f else box.width.toFloat() / box.height
        val (w, h) = if (boxAspect > imgAspect) box.height * imgAspect to box.height.toFloat()
                     else box.width.toFloat() to box.width / imgAspect
        val left = (box.width - w) / 2f
        val top = (box.height - h) / 2f

        Image(
            bitmap = frame, contentDescription = "Live view", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().pointerInput(w, h, left, top) {
                detectTapGestures { o ->
                    val nx = (o.x - left) / w
                    val ny = (o.y - top) / h
                    if (nx in 0f..1f && ny in 0f..1f) onTap(nx, ny)
                }
            },
        )
        if (focus != null) {
            // Focus frame drawn as corner brackets, like the camera's AF area
            Canvas(Modifier.fillMaxSize()) {
                val s = 54.dp.toPx(); val k = 12.dp.toPx(); val sw = 2.dp.toPx()
                val c = Offset(left + focus.first * w, top + focus.second * h)
                val l = c.x - s / 2; val t = c.y - s / 2; val r = c.x + s / 2; val b = c.y + s / 2
                val col = Color(0xFF6FE07A)
                listOf(
                    Offset(l, t) to Offset(l + k, t), Offset(l, t) to Offset(l, t + k),
                    Offset(r, t) to Offset(r - k, t), Offset(r, t) to Offset(r, t + k),
                    Offset(l, b) to Offset(l + k, b), Offset(l, b) to Offset(l, b - k),
                    Offset(r, b) to Offset(r - k, b), Offset(r, b) to Offset(r, b - k),
                ).forEach { (a, e) -> drawLine(col, a, e, sw) }
            }
        }
    }
}
