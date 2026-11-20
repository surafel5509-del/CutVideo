package com.cutvideo.editor.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Project row. The editable document itself lives in [docJson] (kotlinx.serialization), while the
 * duplicated scalar columns are what the home screen, search and sorting need without parsing every
 * document. This hybrid keeps list queries cheap and guarantees the edit graph is stored verbatim.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val openedAt: Long,
    val aspect: String,
    val frameRate: Int,
    val durationUs: Long,
    val clipCount: Int,
    val thumbnailPath: String?,
    val isDraft: Boolean,
    val docJson: String
)

enum class MediaKind { VIDEO, AUDIO }

@Entity(tableName = "media_assets", indices = [Index("projectId")])
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val projectId: String?,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val kind: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val hasAudio: Boolean,
    val waveformPath: String?,
    val addedAt: Long
)

@Entity(tableName = "exports", indices = [Index("projectId")])
data class ExportEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val projectName: String,
    val filePath: String,
    val contentUri: String,
    val thumbnailPath: String?,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val codec: String,
    val sizeBytes: Long,
    val durationUs: Long,
    val createdAt: Long,
    val status: String
)
