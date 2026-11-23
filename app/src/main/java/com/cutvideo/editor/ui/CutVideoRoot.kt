package com.cutvideo.editor.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cutvideo.editor.di.AppContainer
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.ui.screens.audio.AudioEditorScreen
import com.cutvideo.editor.ui.screens.editor.EditorScreen
import com.cutvideo.editor.ui.screens.editor.EditorViewModel
import com.cutvideo.editor.ui.screens.editor.SessionViewModel
import com.cutvideo.editor.ui.screens.effects.EffectsLibraryScreen
import com.cutvideo.editor.ui.screens.export.ExportScreen
import com.cutvideo.editor.ui.screens.export.ExportViewModel
import com.cutvideo.editor.ui.screens.exports.ExportedVideosScreen
import com.cutvideo.editor.ui.screens.exports.ExportedVideosViewModel
import com.cutvideo.editor.ui.screens.home.HomeScreen
import com.cutvideo.editor.ui.screens.home.HomeViewModel
import com.cutvideo.editor.ui.screens.media.MediaPickerScreen
import com.cutvideo.editor.ui.screens.media.MediaPickerViewModel
import com.cutvideo.editor.ui.screens.settings.SettingsScreen
import com.cutvideo.editor.ui.screens.settings.SettingsViewModel
import com.cutvideo.editor.ui.screens.splash.SplashScreen
import com.cutvideo.editor.ui.screens.text.TextEditorScreen

private object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val MEDIA = "media"
    const val SETTINGS = "settings"
    const val EXPORTS = "exports"
    const val CUTOUT = "cutout"
    const val PROJECT_ARG = "projectId"
    const val EDITOR = "editor/{$PROJECT_ARG}"
    const val AUDIO = "audio/{$PROJECT_ARG}"
    const val TEXT = "text/{$PROJECT_ARG}"
    const val EFFECTS = "effects/{$PROJECT_ARG}"
    const val EXPORT = "export/{$PROJECT_ARG}"

    fun editor(id: String) = "editor/$id"
    fun audio(id: String) = "audio/$id"
    fun text(id: String) = "text/$id"
    fun effects(id: String) = "effects/$id"
    fun export(id: String) = "export/$id"
}

/**
 * Single-activity navigation host.
 *
 * The view models are hoisted here (activity scope) so the editing session, export progress and settings
 * survive navigation between the editor, the text/audio/effects studios and the export screen. The heavy
 * engines themselves live in [AppContainer] and are process-scoped.
 */
@Composable
fun CutVideoRoot(
    container: AppContainer,
    incomingMediaUri: Uri?,
    onIncomingMediaConsumed: () -> Unit
) {
    val navController = rememberNavController()

    val homeVm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(container) } }
    )
    val editorVm: EditorViewModel = viewModel(
        factory = viewModelFactory { initializer { EditorViewModel(container) } }
    )
    val sessionVm: SessionViewModel = viewModel(
        factory = viewModelFactory { initializer { SessionViewModel(container) } }
    )
    val exportVm: ExportViewModel = viewModel(
        factory = viewModelFactory { initializer { ExportViewModel(container) } }
    )
    val exportsVm: ExportedVideosViewModel = viewModel(
        factory = viewModelFactory { initializer { ExportedVideosViewModel(container) } }
    )
    val settingsVm: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(container) } }
    )
    val mediaVm: MediaPickerViewModel = viewModel(
        factory = viewModelFactory { initializer { MediaPickerViewModel(container) } }
    )

    var browseTrackType by remember { mutableStateOf(TrackType.VIDEO) }

    /** Pending photo for the cutout studio (URI handed between picker and screen). */
    var cutoutSourceUri by remember { mutableStateOf<Uri?>(null) }

    val browseLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (container.editorController.doc.value == null) {
                editorVm.showMessage("Open a project before importing media")
            } else {
                editorVm.importMedia(uris, browseTrackType)
                editorVm.selectTool(com.cutvideo.editor.ui.screens.editor.EditorTool.NONE)
            }
        }
    }

    val cutoutLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                container.appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            cutoutSourceUri = uri
            navController.navigate(Routes.CUTOUT)
        }
    }

    val imageLayerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val projectId = editorVm.doc.value?.id
        if (uri != null && projectId != null) {
            editorVm.importImageLayer(uri)
        }
    }

    val openProject: (String) -> Unit = { id -> navController.navigate(Routes.editor(id)) }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(140)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(140)) }
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                vm = homeVm,
                onOpenProject = openProject,
                onProjectCreated = openProject,
                onOpenMediaLibrary = { navController.navigate(Routes.MEDIA) },
                onOpenExported = { navController.navigate(Routes.EXPORTS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                incomingMedia = incomingMediaUri,
                onIncomingMediaHandled = onIncomingMediaConsumed
            )
        }

        composable(Routes.MEDIA) {
            MediaPickerScreen(
                vm = mediaVm,
                hasOpenProject = editorVm.doc.value != null,
                onBack = { navController.popBackStack() },
                onImported = { count, isVideo ->
                    val label = if (isVideo) "clip" else "audio file"
                    val plural = if (count == 1) label else "${label}s"
                    editorVm.showMessage("$count $plural added to the timeline")
                    navController.popBackStack()
                },
                onBrowseFiles = {
                    browseTrackType = TrackType.VIDEO
                    browseLauncher.launch(arrayOf("video/*"))
                }
            )
        }

        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument(Routes.PROJECT_ARG) { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString(Routes.PROJECT_ARG).orEmpty()
            EditorScreen(
                vm = editorVm,
                projectId = projectId,
                nativeEngine = container.nativeEngine,
                onBack = {
                    editorVm.saveNow()
                    navController.popBackStack()
                },
                onExport = { navController.navigate(Routes.export(projectId)) },
                onOpenTextEditor = { navController.navigate(Routes.text(projectId)) },
                onOpenEffectsLibrary = { navController.navigate(Routes.effects(projectId)) },
                onOpenAudioEditor = { navController.navigate(Routes.audio(projectId)) },
                onOpenCutout = { cutoutLauncher.launch(arrayOf("image/*")) },
                onPickImageLayer = { imageLayerLauncher.launch(arrayOf("image/*")) }
            )
        }

        composable(
            Routes.TEXT,
            arguments = listOf(navArgument(Routes.PROJECT_ARG) { type = NavType.StringType })
        ) { entry ->
            TextEditorScreen(
                vm = sessionVm,
                projectId = entry.arguments?.getString(Routes.PROJECT_ARG).orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.AUDIO,
            arguments = listOf(navArgument(Routes.PROJECT_ARG) { type = NavType.StringType })
        ) { entry ->
            AudioEditorScreen(
                vm = sessionVm,
                projectId = entry.arguments?.getString(Routes.PROJECT_ARG).orEmpty(),
                onBack = { navController.popBackStack() },
                onPickMusic = {
                    browseTrackType = TrackType.AUDIO
                    browseLauncher.launch(arrayOf("audio/*"))
                }
            )
        }

        composable(
            Routes.EFFECTS,
            arguments = listOf(navArgument(Routes.PROJECT_ARG) { type = NavType.StringType })
        ) { entry ->
            EffectsLibraryScreen(
                vm = sessionVm,
                projectId = entry.arguments?.getString(Routes.PROJECT_ARG).orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.EXPORT,
            arguments = listOf(navArgument(Routes.PROJECT_ARG) { type = NavType.StringType })
        ) { entry ->
            ExportScreen(
                vm = exportVm,
                projectId = entry.arguments?.getString(Routes.PROJECT_ARG).orEmpty(),
                onBack = { navController.popBackStack() },
                onDone = {
                    exportVm.reset()
                    navController.navigate(Routes.EXPORTS) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = settingsVm,
                onBack = { navController.popBackStack() },
                onOpenExported = { navController.navigate(Routes.EXPORTS) }
            )
        }

        composable(Routes.CUTOUT) {
            val source = cutoutSourceUri
            if (source == null) {
                navController.popBackStack()
            } else {
                com.cutvideo.editor.ui.screens.cutout.CutoutScreen(
                    sourceUri = source,
                    onBack = { navController.popBackStack() },
                    onSaveDone = { uri ->
                        editorVm.showMessage(
                            if (uri != null) "Transparent PNG saved to Pictures/CutVideo"
                            else "Could not save the PNG"
                        )
                    },
                    onAddToProject = if (editorVm.doc.value != null) { saved ->
                        editorVm.importImageLayer(saved)
                        navController.popBackStack()
                    } else null
                )
            }
        }

        composable(Routes.EXPORTS) {
            ExportedVideosScreen(
                vm = exportsVm,
                onBack = { navController.popBackStack() },
                onOpenExported = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }
    }
}
