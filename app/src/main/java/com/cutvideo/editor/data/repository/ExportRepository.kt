package com.cutvideo.editor.data.repository

import com.cutvideo.editor.data.db.ExportDao
import com.cutvideo.editor.data.db.ExportEntity
import kotlinx.coroutines.flow.Flow

class ExportRepository(private val dao: ExportDao) {
    val exports: Flow<List<ExportEntity>> = dao.observeAll()

    suspend fun add(entity: ExportEntity) = dao.upsert(entity)
    suspend fun get(id: String): ExportEntity? = dao.get(id)
    suspend fun delete(id: String) = dao.delete(id)
}
