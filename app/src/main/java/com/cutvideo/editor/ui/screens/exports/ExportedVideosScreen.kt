package com.cutvideo.editor.ui.screens.exports

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cutvideo.editor.ui.components.EmptyState
import com.cutvideo.editor.ui.components.ExportCard
import com.cutvideo.editor.ui.components.GradientButton
import com.cutvideo.editor.ui.theme.CutColors

/** Gallery of every export this app has produced, with play / share / delete. */
@Composable
fun ExportedVideosScreen(
    vm: ExportedVideosViewModel,
    onBack: () -> Unit,
    onOpenExported: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exports by vm.exports.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CutColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(CutColors.Surface).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ArrowBack, "Back", tint = CutColors.TextPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Exported videos", style = MaterialTheme.typography.headlineSmall, color = CutColors.TextPrimary)
                Text(
                    "${exports.size} file(s) in your gallery",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary
                )
            }
        }

        if (exports.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Movie,
                title = "Nothing exported yet",
                message = "Finish an edit and hit Export — the rendered MP4 is saved to your gallery and listed here.",
                modifier = Modifier.padding(20.dp),
                action = { GradientButton(text = "Go to projects", onClick = onOpenExported) }
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(exports, key = { it.id }) { export ->
                    Box(Modifier.padding(vertical = 4.dp)) {
                        ExportCard(
                            export = export,
                            onPlay = {
                                // Never fail silently: a dead Play button with no feedback reads as
                                // a broken app. Try a plain viewer first, then a chooser, then say why.
                                val playIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(export.contentUri), "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val started = runCatching {
                                    context.startActivity(playIntent)
                                    true
                                }.recoverCatching {
                                    context.startActivity(Intent.createChooser(playIntent, "Play ${export.projectName}"))
                                    true
                                }.getOrDefault(false)
                                if (!started) {
                                    vm.notify("No video player found on this device")
                                }
                            },
                            onShare = {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, Uri.parse(export.contentUri))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share ${export.projectName}"))
                                }
                            },
                            onDelete = { vm.delete(export) }
                        )
                    }
                }
            }
        }

        if (message != null) {
            Text(
                message!!,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CutColors.SurfaceHighest)
                    .clickable { vm.dismissMessage() }
                    .padding(10.dp)
            )
        }
    }
}
