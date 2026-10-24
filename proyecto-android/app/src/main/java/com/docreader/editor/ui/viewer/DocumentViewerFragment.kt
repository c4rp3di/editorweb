package com.docreader.editor.ui.viewer

import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.docreader.editor.R
import com.docreader.editor.data.engine.SheetModel
import com.docreader.editor.databinding.FragmentViewerBinding
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class DocumentViewerFragment : Fragment() {
    private var _b: FragmentViewerBinding? = null
    private val b get() = _b!!
    private val vm: ViewerViewModel by viewModels()

    private var pfd: ParcelFileDescriptor? = null
    private var pdfAdapter: PdfPagesAdapter? = null
    private var previewing = false
    private var docxController: DocxEditorController? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val img = withContext(Dispatchers.IO) { DocxEditorController.decodeImage(requireContext(), uri) }
            if (img == null) toast("No se pudo leer la imagen") else docxController?.insertImage(img)
        }
    }

    private val saveAs = registerForActivityResult(CreateDocAs()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            toast(if (vm.save(uri)) "Guardado" else "No se pudo guardar")
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentViewerBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
        b.btnSave.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (vm.save(null)) toast("Guardado") else saveAs.launch(vm.mime() to vm.suggestedName())
            }
        }
        b.btnPreview.setOnClickListener { togglePreview() }
    }

    private fun render(s: ViewerState) {
        b.progress.isVisible = s is ViewerState.Loading
        b.bar.isVisible = s is ViewerState.Text || s is ViewerState.Docx || s is ViewerState.Xlsx
        b.error.isVisible = s is ViewerState.Error
        b.textScroll.isVisible = s is ViewerState.Text && !previewing
        b.docxList.isVisible = s is ViewerState.Docx
        b.docxToolbar.root.isVisible = s is ViewerState.Docx
        b.gridScroll.isVisible = s is ViewerState.Xlsx
        b.sheetSpinner.isVisible = s is ViewerState.Xlsx && (vm.xlsx?.sheets?.size ?: 0) > 1
        b.pages.isVisible = s is ViewerState.Pdf
        when (s) {
            is ViewerState.Text -> showText(s)
            is ViewerState.Docx -> showDocx()
            is ViewerState.Xlsx -> showXlsx()
            is ViewerState.Pdf -> showPdf(s)
            is ViewerState.Error -> b.error.text = s.message
            ViewerState.Loading -> Unit
        }
    }

    private fun showText(s: ViewerState.Text) {
        if (b.editor.tag != null) return
        b.editor.tag = true
        b.editor.setText(vm.draft)
        b.editor.doAfterTextChanged { vm.draft = it?.toString().orEmpty() }
        if (!s.editable) {
            b.editor.keyListener = null
            b.editor.setTextIsSelectable(true)
            b.btnSave.isVisible = false
        }
        b.btnPreview.isVisible = s.ext == "md" || s.ext == "markdown"
    }

    private fun togglePreview() {
        previewing = !previewing
        b.textScroll.isVisible = !previewing
        b.previewScroll.isVisible = previewing
        b.btnPreview.setText(if (previewing) R.string.edit else R.string.preview)
        if (previewing) Markwon.create(requireContext()).setMarkdown(b.preview, vm.draft)
    }

    private fun showDocx() {
        if (docxController != null) return
        val d = vm.docx ?: return
        docxController = DocxEditorController(requireContext(), b.docxList, b.docxToolbar, d) {
            pickImage.launch("image/*")
        }
    }

    private fun showXlsx() {
        if (b.gridScroll.tag != null) return
        val m = vm.xlsx ?: return
        b.gridScroll.tag = true
        if (m.sheets.size > 1) {
            b.sheetSpinner.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, m.sheets.map { it.name }
            )
            b.sheetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = bindSheet(m.sheets[pos])
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        } else {
            bindSheet(m.sheets[0])
        }
    }

    private fun bindSheet(s: SheetModel) {
        val cellPx = (110 * resources.displayMetrics.density).toInt()
        b.grid.layoutManager = GridLayoutManager(requireContext(), s.cols)
        b.grid.layoutParams = b.grid.layoutParams.apply { width = s.cols * cellPx }
        lateinit var adapter: CellAdapter
        adapter = CellAdapter(s) { ref, pos ->
            val input = EditText(requireContext()).apply {
                setText(s.get(ref))
                setSelection(text.length)
                hint = "Texto, número o =fórmula"
            }
            AlertDialog.Builder(requireContext())
                .setTitle("${s.name}!$ref")
                .setView(input)
                .setPositiveButton("Aceptar") { _, _ ->
                    val v = input.text.toString()
                    if (v == (s.orig[ref] ?: "")) s.edits.remove(ref) else s.edits[ref] = v
                    adapter.notifyItemChanged(pos)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        b.grid.adapter = adapter
    }

    private fun showPdf(s: ViewerState.Pdf) {
        if (pdfAdapter != null) return
        try {
            val p = requireContext().contentResolver.openFileDescriptor(s.uri, "r")
                ?: throw java.io.IOException()
            pfd = p
            val renderer = PdfRenderer(p)
            pdfAdapter = PdfPagesAdapter(
                renderer, resources.displayMetrics.widthPixels, viewLifecycleOwner.lifecycleScope
            )
            b.pages.layoutManager = LinearLayoutManager(requireContext())
            b.pages.adapter = pdfAdapter
        } catch (e: SecurityException) {
            fail("PDF protegido con contraseña: no soportado")
        } catch (e: Exception) {
            fail("Archivo no válido")
        }
    }

    private fun fail(msg: String) {
        b.pages.isVisible = false
        b.error.isVisible = true
        b.error.text = msg
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        docxController = null
        b.pages.adapter = null
        pdfAdapter?.close(); pdfAdapter = null
        runCatching { pfd?.close() }; pfd = null
        _b = null
    }
}
