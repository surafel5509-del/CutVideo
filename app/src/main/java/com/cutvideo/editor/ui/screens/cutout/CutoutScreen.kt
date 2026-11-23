package com.cutvideo.editor.ui.screens.cutout

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cutvideo.editor.domain.cutout.CutoutEngine
import com.cutvideo.editor.ui.components.GhostButton
import com.cutvideo.editor.ui.components.SegmentedControl
import com.cutvideo.editor.ui.components.ToggleRow
import com.cutvideo.editor.ui.components.ValueSlider
import com.cutvideo.editor.ui.theme.CutColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual photo background remover screen.
 *
 * Real brush engine below ([CutoutEngine]): erase/restore with size, hardness and opacity, move
 * (zoom/pan) mode, undo/redo, edge refinement, a true checkerboard alpha preview, transparent PNG
 * export through MediaStore and an optional "add as layer" callback that places the result on the
 * timeline as an image overlay. The source photo is never modified.
 */
@Composable
fun CutoutScreen(
    sourceUri: Uri,
    onBack: () -> Unit,
    onSaveDone: (Uri?) -> Unit = {},
    onAddToProject: ((Uri) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf<CutoutEngine?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var workingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }

    var toolIndex by remember { mutableIntStateOf(0) } // 0 erase, 1 restore, 2 move
    var brushSize by remember { mutableFloatStateOf(64f) }
    var hardness by remember { mutableFloatStateOf(0.5f) }
    var brushOpacity by remember { mutableFloatStateOf(1f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(sourceUri) {
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = context.contentResolver.openInputStream(sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: error("Could not decode the image")
                val argb = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val eng = CutoutEngine.fromBitmap(argb)
                withContext(Dispatchers.Main) {
                    engine = eng
                    workingBitmap = argb
                }
            }.onFailure {
                withContext(Dispatchers.Main) { loadError = it.message ?: "Could not open the image" }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C10))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = CutColors.TextPrimary)
            }
            Text(
                "Photo cutout",
                style = MaterialTheme.typography.titleLarge,
                color = CutColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            GhostButton("Undo") {
                val eng = engine ?: return@GhostButton
                if (eng.undo()) revision++
            }
            GhostButton("Redo") {
                val eng = engine ?: return@GhostButton
                if (eng.redo()) revision++
            }
        }

        when {
            loadError != null -> Text(
                "Error: $loadError",
                color = CutColors.Danger,
                modifier = Modifier.padding(16.dp)
            )
            engine == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CutColors.Accent)
            }
            else -> {
                val eng = engine!!
                val source = workingBitmap!!

                // --- painting canvas -----------------------------------------------------------
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF17171D))
                        .border(1.dp, CutColors.Border, RoundedCornerShape(14.dp))
                ) {
                    val moveMode = toolIndex == 2
                    val frame = remember(source, revision) { source.asImageBitmap() }
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(source.width.toFloat() / source.height)
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                translationX = pan.x,
                                translationY = pan.y
                            )
                            .transformable(
                                state = rememberTransformableState { zoomChange, offsetChange, _ ->
                                    if (moveMode) {
                                        zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                                        pan += offsetChange
                                        if (zoom <= 1f) pan = Offset.Zero
                                    }
                                }
                            )
                            .pointerInput(eng, toolIndex, brushSize, hardness, brushOpacity, zoom, pan) {
                                if (!moveMode) {
                                    detectDragGestures(
                                        onDragStart = {
                                            eng.beginStroke()
                                        },
                                        onDrag = { change, _ ->
                                            // Hit-testing undoes our own graphicsLayer, so the
                                            // position is already in image-layout space.
                                            val scaleX = source.width.toFloat() / size.width
                                            val scaleY = source.height.toFloat() / size.height
                                            val imgX = change.position.x * scaleX
                                            val imgY = change.position.y * scaleY
                                            val prev = change.previousPosition
                                            val px = prev.x * scaleX
                                            val py = prev.y * scaleY
                                            val tool =
                                                if (toolIndex == 0) CutoutEngine.Tool.ERASE
                                                else CutoutEngine.Tool.RESTORE
                                            val dirty = eng.stroke(
                                                tool, px, py, imgX, imgY,
                                                brushSize * scaleX, hardness, brushOpacity
                                            )
                                            repaintRegion(source, eng, dirty)
                                            revision++
                                        }
                                    )
                                }
                            }
                    ) {
                        drawCheckerboard()
                        drawImage(frame)
                    }

                    if (saving) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CutColors.Accent)
                        }
                    }
                }

                // --- controls ------------------------------------------------------------------
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SegmentedControl(
                        options = listOf("Erase", "Restore", "Move / zoom"),
                        selectedIndex = toolIndex,
                        onSelect = { toolIndex = it }
                    )
                    Spacer(Modifier.height(8.dp))
                    if (toolIndex < 2) {
                        ValueSlider(
                            "Brush size", brushSize,
                            onValueChange = { brushSize = it },
                            valueRange = 8f..220f,
                            defaultValue = 64f
                        )
                        ValueSlider(
                            "Hardness", hardness,
                            onValueChange = { hardness = it },
                            valueRange = 0f..1f,
                            defaultValue = 0.5f
                        )
                        ValueSlider(
                            "Brush opacity", brushOpacity,
                            onValueChange = { brushOpacity = it },
                            valueRange = 0.05f..1f,
                            defaultValue = 1f
                        )
                    } else {
                        ValueSlider(
                            "Zoom", zoom,
                            onValueChange = { zoom = it.coerceIn(1f, 8f) },
                            valueRange = 1f..8f,
                            defaultValue = 1f
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Refine edges") {
                            eng.refineEdges(1, 1)
                            repaintAll(source, eng)
                            revision++
                        }
                        GhostButton("Soften edges") {
                            eng.featherAll(0.25f)
                            repaintAll(source, eng)
                            revision++
                        }
                        GhostButton("Start over") {
                            eng.resetAlpha()
                            repaintAll(source, eng)
                            revision++
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Visible: ${(eng.visibleFraction() * 100).toInt()}% • the original photo stays intact",
                        style = MaterialTheme.typography.bodySmall,
                        color = CutColors.TextTertiary
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Export transparent PNG") {
                            if (!saving) {
                                saving = true
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) {
                                        saveTransparentPng(context, eng, source)
                                    }
                                    saving = false
                                    lastSavedUri = uri
                                    onSaveDone(uri)
                                }
                            }
                        }
                        val saved = lastSavedUri
                        if (saved != null && onAddToProject != null) {
                            GhostButton("Add as layer") { onAddToProject(saved) }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

private fun repaintRegion(source: Bitmap, eng: CutoutEngine, dirty: IntArray) {
    val (x, y, w, h) = dirty
    if (w <= 0 || h <= 0) return
    val pixels = eng.compositeRegion(x, y, w, h)
    source.setPixels(pixels, 0, w, x, y, w, h)
}

private fun repaintAll(source: Bitmap, eng: CutoutEngine) {
    source.setPixels(eng.composite(), 0, source.width, 0, 0, source.width, source.height)
}

private fun DrawScope.drawCheckerboard() {
    val cell = 18.dp.toPx()
    clipRect {
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = 0
            while (x < size.width) {
                drawRect(
                    color = if ((row + col) % 2 == 0) Color(0xFF23232B) else Color(0xFF2C2C36),
                    topLeft = Offset(x, y),
                    size = Size(cell, cell)
                )
                x += cell
                col++
            }
            y += cell
            row++
        }
    }
}

/** Writes the composited result as a transparent PNG into Pictures/CutVideo. */
private suspend fun saveTransparentPng(
    context: android.content.Context,
    eng: CutoutEngine,
    source: Bitmap
): Uri? = withContext(Dispatchers.IO) {
    runCatching {
        val out = Bitmap.createBitmap(eng.width, eng.height, Bitmap.Config.ARGB_8888)
        out.setPixels(eng.composite(), 0, eng.width, 0, 0, eng.width, eng.height)
        val name = "cutout_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CutVideo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the PNG entry")
        resolver.openOutputStream(uri)?.use { stream ->
            if (!out.compress(Bitmap.CompressFormat.PNG, 100, stream)) error("PNG encode failed")
        } ?: error("Could not open the PNG for writing")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        out.recycle()
        uri
    }.getOrNull()
}
