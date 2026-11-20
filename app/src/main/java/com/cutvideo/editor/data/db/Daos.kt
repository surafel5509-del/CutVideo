package com.cutvideo.editor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE isDraft = 1 ORDER BY updatedAt DESC")
    fun observeDrafts(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProjectEntity)

    @Query("UPDATE projects SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE projects SET thumbnailPath = :path WHERE id = :id")
    suspend fun updateThumbnail(id: String, path: String?)

    @Query("UPDATE projects SET isDraft = :draft WHERE id = :id")
    suspend fun setDraft(id: String, draft: Boolean)

    @Query("UPDATE projects SET openedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MediaDao {

    @Query("SELECT * FROM media_assets WHERE projectId = :projectId ORDER BY addedAt ASC")
    fun observeForProject(projectId: String): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE id = :id")
    suspend fun get(id: String): MediaAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaAssetEntity)

    @Query("UPDATE media_assets SET waveformPath = :path WHERE id = :id")
    suspend fun setWaveform(id: String, path: String?)

    @Query("DELETE FROM media_assets WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

@Dao
interface ExportDao {

    @Query("SELECT * FROM exports ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExportEntity>>

    @Query("SELECT * FROM exports WHERE id = :id")
    suspend fun get(id: String): ExportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExportEntity)

    @Query("DELETE FROM exports WHERE id = :id")
    suspend fun delete(id: String)
}
