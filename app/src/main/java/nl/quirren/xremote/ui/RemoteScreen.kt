package nl.quirren.xremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.quirren.xremote.PropUi
import nl.quirren.xremote.RemoteViewModel
import nl.quirren.xremote.ptp.FujiProp
import nl.quirren.xremote.ptp.KnownProps
import nl.quirren.xremote.ptp.PropGroup
import java.util.Locale

@Composable
fun RemoteScreen(vm: RemoteViewModel, onShowLog: () -> Unit) {
    val props by vm.props.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val fps by vm.fps.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val name by vm.cameraName.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Int?>(null) }

    Row(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        ControlGrid(
            props = props.filter { it.group != PropGroup.STATUS && it.code != FujiProp.FOCUS_POINT },
            selected = selected,
            onSelect = { selected = if (selected == it) null else it },
            modifier = Modifier.width(264.dp).fillMaxHeight().background(Panel),
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            LiveView(frame, focus, onTap = vm::tapFocus, modifier = Modifier.fillMaxSize())
            StatusBar(name, props, fps, Modifier.align(Alignment.TopCenter).padding(8.dp))
            val sel = selected?.let { c -> props.find { it.code == c } }
            if (sel != null) {
                ValuePicker(
                    prop = sel,
                    onPick = { vm.setProp(sel.code, it) },
                    onClose = { selected = null },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth(),
                )
            }
        }
        ShutterRail(busy, onShutter = vm::shutter, onLog = onShowLog, onDisconnect = vm::disconnect)
    }
}

@Composable
private fun ControlGrid(props: List<PropUi>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier) {
    val groups = props.groupBy { it.group }.toSortedMap(compareBy { it.ordinal })
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        groups.forEach { (group, list) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_${group.name}") {
                Text(group.title.uppercase(), color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
            }
            items(list, key = { it.code }) { p -> PropTile(p, p.code == selected) { onSelect(p.code) } }
        }
    }
}

@Composable
private fun PropTile(p: PropUi, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .clip(shape)
            .background(if (selected) TileHi else Tile)
            .then(if (selected) Modifier.border(1.5.dp, Accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .alpha(if (p.writable) 1f else 0.5f)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(p.short, color = if (selected) Accent else TextDim, fontSize = 10.sp, maxLines = 1)
        Text(p.text, color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LiveView(frame: ImageBitmap?, focus: Pair<Float, Float>?, onTap: (Float, Float) -> Unit, modifier: Modifier) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.background(Color.Black).onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
        if (frame == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                Spacer(Modifier.height(10.dp))
                Text("Waiting for live view", color = TextDim)
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
            Canvas(Modifier.fillMaxSize()) {
                val s = 56.dp.toPx()
                val c = Offset(left + focus.first * w, top + focus.second * h)
                drawRect(Accent, topLeft = Offset(c.x - s / 2, c.y - s / 2), size = Size(s, s), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun StatusBar(name: String, props: List<PropUi>, fps: Float, modifier: Modifier) {
    val status = props.filter { it.group == PropGroup.STATUS }
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        status.forEach { Text("${it.short} ${it.text}", color = TextHi, fontSize = 12.sp) }
        Text(String.format(Locale.US, "%.0f fps", fps), color = TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun ValuePicker(prop: PropUi, onPick: (Long) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier.widthIn(max = 720.dp).clip(RoundedCornerShape(14.dp)).background(Panel.copy(alpha = 0.94f)).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(prop.name, color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Text(prop.raw, color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Close", tint = TextDim) }
        }
        when {
            !prop.writable -> Text(
                "Read-only right now. On the X-T3 this usually means its dial or ring isn't on A, or the camera doesn't allow it over WiFi.",
                color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(4.dp),
            )
            prop.choices.isNotEmpty() -> {
                val state = rememberLazyListState()
                val idx = prop.choices.indexOf(prop.value)
                LaunchedEffect(prop.code) { if (idx > 2) state.scrollToItem(idx - 2) }
                LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(prop.choices) { _, v ->
                        val cur = v == prop.value
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (cur) Accent else Tile)
                                .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(v) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(KnownProps.format(prop.code, v), color = if (cur) Bg else TextHi,
                                fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
                        }
                    }
                }
            }
            prop.range != null -> prop.range?.let { r ->
                val step: Long = if (r.step <= 0L) 1L else r.step
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { onPick((prop.value - step).coerceAtLeast(r.min)) }) { Icon(Icons.Default.Remove, "Less", tint = TextHi) }
                    Text(prop.text, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                    IconButton(onClick = { onPick((prop.value + step).coerceAtMost(r.max)) }) { Icon(Icons.Default.Add, "More", tint = TextHi) }
                }
            }
            else -> Text("The camera didn't report the allowed values for this setting.", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun ShutterRail(busy: Boolean, onShutter: () -> Unit, onLog: () -> Unit, onDisconnect: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.width(104.dp).fillMaxHeight().background(Panel).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onLog) { Icon(Icons.Default.Terminal, "Log", tint = TextDim) }
        Box(
            Modifier.size(78.dp).clip(CircleShape).border(4.dp, TextHi, CircleShape)
                .clickable(enabled = !busy) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onShutter() }
                .padding(9.dp).clip(CircleShape).background(if (busy) TileHi else Accent),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Accent, strokeWidth = 3.dp)
        }
        IconButton(onClick = onDisconnect) { Icon(Icons.Default.LinkOff, "Disconnect", tint = Danger) }
    }
}
