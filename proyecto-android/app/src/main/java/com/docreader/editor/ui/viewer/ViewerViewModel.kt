package com.docreader.editor.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docreader.editor.data.docx.DocxDoc
import com.docreader.editor.data.engine.DocumentEngine
import com.docreader.editor.data.engine.DocumentHandle
import com.docreader.editor.data.engine.EngineRegistry
import com.docreader.editor.data.xlsx.XlsxDoc
import com.docreader.editor.data.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ViewerState {
    object Loading : ViewerState
    data class Text(val ext: String, val editable: Boolean) : ViewerState
    object Docx : ViewerState
    object Xlsx : ViewerState
    data class Pdf(val uri: Uri) : ViewerState
    data class Error(val message: String) : ViewerState
}

@HiltViewModel
class ViewerViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val registry: EngineRegistry,
    private val repo: DocumentRepository,
    saved: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state

    /** Texto en edición (formatos de texto); vive en el ViewModel y sobrevive a la rotación. */
    var draft: String = ""
    var ext: String = ""
    var name: String = "documento"
    private var engine: DocumentEngine? = null
    private var handle: DocumentHandle? = null

    val docx: DocxDoc? get() = handle?.model as? DocxDoc
    val xlsx: XlsxDoc? get() = handle?.model as? XlsxDoc

    init {
        val uriStr = saved.get<String>("uri")
        if (uriStr == null) _state.value = ViewerState.Error("Documento no especificado")
        else load(Uri.parse(uriStr))
    }

    private fun load(uri: Uri) = viewModelScope.launch {
        try {
            name = repo.displayName(uri)
            ext = repo.extensionOf(uri)
            repo.touch(uri)
            when (ext) {
                "docm" -> _state.value = ViewerState.Error("Los documentos con macros (.docm) no están soportados")
                "pdf" -> _state.value = ViewerState.Pdf(uri)
                else -> {
                    val e = registry.forExtension(ext)
                    val h = e.open(ctx, uri, ext)
                    engine = e; handle = h; draft = h.text
                    _state.value = when (h.model) {
                        is DocxDoc -> ViewerState.Docx
                        is XlsxDoc -> ViewerState.Xlsx
                        else -> ViewerState.Text(ext, e.supportsEditing)
                    }
                }
            }
        } catch (ex: Exception) {
            _state.value = ViewerState.Error("Archivo no válido o ilegible")
        }
    }

    /** Guarda en el archivo original (outputUri == null) o en el Uri dado. */
    suspend fun save(outputUri: Uri?): Boolean {
        val e = engine ?: return false
        val h = handle ?: return false
        if (h.model == null) h.text = draft
        return e.save(ctx, h, outputUri ?: h.uri)
    }

    fun mime(): String = when (ext) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        else -> "text/plain"
    }

    fun suggestedName(): String {
        val base = name.substringBeforeLast('.', name)
        return "${base}_editado.${ext.ifEmpty { "txt" }}"
    }
}
