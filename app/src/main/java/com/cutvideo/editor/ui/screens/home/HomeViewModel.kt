package com.cutvideo.editor.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutvideo.editor.data.repository.AppSettings
import com.cutvideo.editor.data.repository.ProjectSummary
import com.cutvideo.editor.di.AppContainer
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ClipAnimationSpec
import com.cutvideo.editor.domain.model.ClipAnimationType
import com.cutvideo.editor.domain.model.Ids
import com.cutvideo.editor.domain.model.TextSpec
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.domain.timeline.TimelineManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val aspect: AspectRatio,
    val frameRate: Int,
    val accentIndex: Int,
    val addTitle: Boolean
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    val projects: StateFlow<List<ProjectSummary>> = container.projectRepository.projects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val drafts: StateFlow<List<ProjectSummary>> = container.projectRepository.drafts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val templates = listOf(
        ProjectTemplate(
            id = "tiktok",
            name = "Vertical Story",
            description = "9:16 • captions + fast cuts for short-form",
            aspect = AspectRatio.RATIO_9_16,
            frameRate = 30,
            accentIndex = 0,
            addTitle = true
        ),
        ProjectTemplate(
            id = "youtube",
            name = "YouTube Cut",
            description = "16:9 • 1080p timeline with title card",
            aspect = AspectRatio.RATIO_16_9,
            frameRate = 30,
            accentIndex = 1,
            addTitle = true
        ),
        ProjectTemplate(
            id = "square",
            name = "Square Post",
            description = "1:1 • feed-ready social clip",
            aspect = AspectRatio.RATIO_1_1,
            frameRate = 30,
            accentIndex = 2,
            addTitle = false
        ),
        ProjectTemplate(
            id = "cinematic",
            name = "Cinematic 4:5",
            description = "4:5 • 24 fps film look",
            aspect = AspectRatio.RATIO_4_5,
            frameRate = 24,
            accentIndex = 3,
            addTitle = true
        )
    )

    /**
     * Creates a project on disk, then hands the id back once it is saved — the editor loads from the
     * repository, so the row must exist before navigation happens.
     */
    fun createProject(
        name: String,
        aspect: AspectRatio,
        frameRate: Int,
        template: ProjectTemplate? = null,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            val title = name.trim().ifBlank { "Untitled project" }
            var document = TimelineManager.createProject(title, aspect, frameRate)
            if (template?.addTitle == true) {
                val textTrack = document.tracks.firstOrNull { it.type == TrackType.TEXT }
                if (textTrack != null) {
                    document = TimelineManager.addClipToTrack(
                        document,
                        textTrack.id,
                        Clip(
                            id = Ids.clip(),
                            trackType = TrackType.TEXT,
                            name = "Title",
                            startUs = 0L,
                            durationUs = 3_000_000L,
                            text = TextSpec(text = title, sizeSp = 56f, yFrac = 0.5f),
                            animationIn = ClipAnimationSpec(
                                type = ClipAnimationType.ZOOM_IN,
                                durationUs = 600_000L
                            )
                        )
                    )
                }
            }
            container.projectRepository.save(document, null, isDraft = true)
            container.projectRepository.refreshCount()
            onCreated(document.id)
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            container.projectRepository.delete(id)
            _message.value = "Project deleted"
        }
    }

    fun renameProject(id: String, name: String) {
        viewModelScope.launch { container.projectRepository.rename(id, name.trim().ifBlank { "Untitled project" }) }
    }

    fun duplicateProject(summary: ProjectSummary) {
        viewModelScope.launch {
            val doc = container.projectRepository.load(summary.id) ?: return@launch
            val copy = doc.copy(
                id = com.cutvideo.editor.domain.model.Ids.project(),
                name = "${doc.name} copy",
                createdAt = System.currentTimeMillis()
            )
            container.projectRepository.save(copy, null, isDraft = true)
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
