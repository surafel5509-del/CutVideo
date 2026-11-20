package com.cutvideo.editor.di

import android.content.Context
import com.cutvideo.editor.data.db.AppDatabase
import com.cutvideo.editor.data.repository.MediaRepository
import com.cutvideo.editor.data.repository.ProjectRepository
import com.cutvideo.editor.data.repository.SettingsRepository
import com.cutvideo.editor.data.repository.ExportRepository
import com.cutvideo.editor.editor.EditorController
import com.cutvideo.editor.editor.PreviewController
import com.cutvideo.editor.export.ExportController
import com.cutvideo.editor.media.MediaImporter
import com.cutvideo.editor.media.ThumbnailStore
import com.cutvideo.editor.nativebridge.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Manual dependency container (no annotation-processing DI needed for an app of this size).
 * Everything is created lazily from the application context and lives for the process lifetime.
 */
class AppContainer(context: Context) {

    val appContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val projectRepository: ProjectRepository by lazy { ProjectRepository(database.projectDao()) }
    val mediaRepository: MediaRepository by lazy { MediaRepository(database.mediaDao()) }
    val exportRepository: ExportRepository by lazy { ExportRepository(database.exportDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val nativeEngine: NativeEngine by lazy { NativeEngine() }
    val thumbnailStore: ThumbnailStore by lazy { ThumbnailStore(appContext) }
    val mediaImporter: MediaImporter by lazy {
        MediaImporter(appContext, mediaRepository, thumbnailStore, nativeEngine)
    }
    val exportController: ExportController by lazy {
        ExportController(
            context = appContext,
            nativeEngine = nativeEngine,
            exportRepository = exportRepository,
            thumbnailStore = thumbnailStore,
            scope = applicationScope
        )
    }

    /** One editing session shared by the editor, audio, text, effects and export screens. */
    val editorController: EditorController by lazy {
        EditorController(
            context = appContext,
            projectRepository = projectRepository,
            mediaRepository = mediaRepository,
            settingsRepository = settingsRepository,
            mediaImporter = mediaImporter,
            thumbnailStore = thumbnailStore,
            nativeEngine = nativeEngine,
            scope = applicationScope
        )
    }

    val previewController: PreviewController by lazy {
        PreviewController(appContext, nativeEngine)
    }

    /** Set when a project document is too large / not yet ready; drives the recovery banner. */
    val lastRecoveryNotice = MutableStateFlow<String?>(null)

    init {
        applicationScope.launch {
            runCatching { projectRepository.refreshCount() }
        }
    }
}
