package com.cutvideo.editor.data.repository

import com.cutvideo.editor.core.TimeText
import com.cutvideo.editor.data.db.MediaAssetEntity
import com.cutvideo.editor.data.db.ProjectDao
import com.cutvideo.editor.data.db.ProjectEntity
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.timeline.TimelineManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

/** Lightweight projection of a project for list UIs. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val createdAt: Long,
    val durationUs: Long,
    val clipCount: Int,
    val thumbnailPath: String?,
    val aspect: AspectRatio,
    val isDraft: Boolean
) {
    val durationLabel: String get() = TimeText.clock(durationUs)
    val dateLabel: String get() = TimeText.relativeDate(updatedAt)
}

class ProjectRepository(private val dao: ProjectDao) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    val projects: Flow<List<ProjectSummary>> = dao.observeAll().map { rows -> rows.map { it.toSummary() } }
    val drafts: Flow<List<ProjectSummary>> = dao.observeDrafts().map { rows -> rows.map { it.toSummary() } }
    val projectCount = MutableStateFlow(0)

    suspend fun refreshCount() {
        projectCount.value = dao.count()
    }

    suspend fun load(id: String): ProjectDoc? {
        val row = dao.get(id) ?: return null
        dao.touch(id, System.currentTimeMillis())
        return decode(row) ?: run {
            // Corrupt payload: never crash the editor, start from an empty doc with the same identity.
            TimelineManager.createProject(row.name, AspectRatio.valueOf(row.aspect), row.frameRate, row.id)
        }
    }

    suspend fun exists(id: String): Boolean = dao.get(id) != null

    suspend fun save(doc: ProjectDoc, thumbnailPath: String? = null, isDraft: Boolean = false) {
        val existing = dao.get(doc.id)
        dao.upsert(
            ProjectEntity(
                id = doc.id,
                name = doc.name,
                createdAt = existing?.createdAt ?: doc.createdAt,
                updatedAt = System.currentTimeMillis(),
                openedAt = System.currentTimeMillis(),
                aspect = doc.aspect.name,
                frameRate = doc.frameRate,
                durationUs = TimelineManager.projectDurationUs(doc),
                clipCount = doc.clipCount(),
                thumbnailPath = thumbnailPath ?: existing?.thumbnailPath,
                isDraft = isDraft,
                docJson = json.encodeToString(ProjectDoc.serializer(), doc)
            )
        )
    }

    suspend fun rename(id: String, name: String) = dao.rename(id, name, System.currentTimeMillis())

    suspend fun setThumbnail(id: String, path: String?) = dao.updateThumbnail(id, path)

    suspend fun delete(id: String) = dao.delete(id)

    private fun decode(row: ProjectEntity): ProjectDoc? = try {
        json.decodeFromString(ProjectDoc.serializer(), row.docJson)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun ProjectEntity.toSummary() = ProjectSummary(
        id = id,
        name = name,
        updatedAt = updatedAt,
        createdAt = createdAt,
        durationUs = durationUs,
        clipCount = clipCount,
        thumbnailPath = thumbnailPath,
        aspect = runCatching { AspectRatio.valueOf(aspect) }.getOrDefault(AspectRatio.RATIO_16_9),
        isDraft = isDraft
    )
}

/** Media assets (imported clips) belonging to a project. */
class MediaRepository(private val dao: com.cutvideo.editor.data.db.MediaDao) {
    fun assets(projectId: String): Flow<List<MediaAssetEntity>> = dao.observeForProject(projectId)
    suspend fun add(asset: MediaAssetEntity) = dao.upsert(asset)
    suspend fun get(id: String): MediaAssetEntity? = dao.get(id)
    suspend fun setWaveform(id: String, path: String?) = dao.setWaveform(id, path)
    suspend fun clear(projectId: String) = dao.deleteForProject(projectId)
}
