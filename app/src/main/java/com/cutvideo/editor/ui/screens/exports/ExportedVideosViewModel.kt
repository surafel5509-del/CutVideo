package com.cutvideo.editor.ui.screens.exports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutvideo.editor.data.db.ExportEntity
import com.cutvideo.editor.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExportedVideosViewModel(private val container: AppContainer) : ViewModel() {

    val exports: StateFlow<List<ExportEntity>> = container.exportRepository.exports
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun delete(export: ExportEntity) {
        viewModelScope.launch {
            container.exportController.deleteExport(export.id, export.contentUri)
            _message.value = "Removed ${export.projectName}"
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
