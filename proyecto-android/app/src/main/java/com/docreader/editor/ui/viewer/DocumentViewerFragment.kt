package com.docreader.editor.ui.viewer

import android.os.Bundle
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

    private var pdfController: PdfEditorController? = null
    private var pendingKind = ""
    private var previewing = false
    private var docxController: DocxEditorController? = null
    private var xlsxController: XlsxEditorController? = null
    private var pagePreview: PagePreviewController? = null
    private var pagePreviewing = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val img = withContext(Dispatchers.IO) { DocxEditorController.decodeImage(requireContext(), uri) }
            if (img == null) toast("No se pudo leer la imagen") else docxController?.insertImage(img)
        }
    }

    private val pickPdfFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pdfController?.mergeFrom(it) }
    }

    private val createTarget = registerForActivityResult(CreateDocAs()) { uri ->
        uri?.let { pdfController?.onTarget(pendingKind, it) }
    }

    private val saveAs = registerForActivityResult(CreateDocAs()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = vm.save(uri)
            toast(if (ok) "Guardado" else "No se pudo guardar")
            if (ok) pdfController?.onSaved()
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
                if (vm.save(null)) { toast("Guardado"); pdfController?.onSaved() } else saveAs.launch(vm.mime() to vm.suggestedName())
            }
        }
        b.btnPreview.setOnClickListener { togglePreview() }
    }

    private fun render(s: ViewerState) {
        if (pagePreviewing) return
        b.progress.isVisible = s is ViewerState.Loading
        b.bar.isVisible = s is ViewerState.Text || s is ViewerState.Docx || s is ViewerState.Xlsx || s is ViewerState.Pdf
        b.error.isVisible = s is ViewerState.Error
        b.textScroll.isVisible = s is ViewerState.Text && !previewing
        b.docxList.isVisible = s is ViewerState.Docx
        b.docxToolbar.root.isVisible = s is ViewerState.Docx
        b.gridScroll.isVisible = s is ViewerState.Xlsx
        b.xlsxToolbar.root.isVisible = s is ViewerState.Xlsx
        b.fxBar.isVisible = s is ViewerState.Xlsx
        b.tabsScroll.isVisible = s is ViewerState.Xlsx
        b.pdfScroll.isVisible = s is ViewerState.Pdf
        b.pdfToolbar.root.isVisible = s is ViewerState.Pdf
        when (s) {
            is ViewerState.Text -> showText(s)
            is ViewerState.Docx -> showDocx()
            is ViewerState.Xlsx -> showXlsx()
            is ViewerState.Pdf -> showPdf()
            ViewerState.PdfPassword -> passwordDialog()
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
        docxController = DocxEditorController(
            requireContext(), b.docxList, b.docxToolbar, d,
            { pickImage.launch("image/*") },
            { showDocxPreview() }
        )
    }

    private fun showDocxPreview() {
        val d = vm.docx ?: return
        val blocks = d.snapshot()
        toast("Calculando páginas…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pages = withContext(Dispatchers.Default) { DocxPaginator(d, blocks).paginate() }
                openPreview("Vista de páginas (aproximada)", pages)
            } catch (e: Exception) {
                toast("No se pudo calcular la paginación")
            }
        }
    }

    private fun showXlsxPreview() {
        val x = xlsxController ?: return
        try {
            val sh = x.currentSheet()
            openPreview("Impresión · ${sh.name}", SheetPaginator.paginate(x.doc, sh))
        } catch (e: Exception) {
            toast("No se pudo calcular la vista de impresión")
        }
    }

    private fun openPreview(title: String, pages: List<PreviewPage>) {
        pagePreviewing = true
        listOf<View>(b.docxToolbar.root, b.docxList, b.xlsxToolbar.root, b.fxBar, b.gridScroll, b.tabsScroll)
            .forEach { it.isVisible = false }
        pagePreview = PagePreviewController(requireContext(), b) {
            pagePreviewing = false
            pagePreview = null
            render(vm.state.value)
        }.also { it.show(title, pages) }
    }

    private fun showXlsx() {
        if (xlsxController != null) return
        val d = vm.xlsx ?: return
        xlsxController = XlsxEditorController(requireContext(), b, d) { showXlsxPreview() }
    }

    private fun showPdf() {
        if (pdfController != null) return
        val sess = vm.pdf ?: return
        pdfController = PdfEditorController(
            requireContext(), b, sess, viewLifecycleOwner.lifecycleScope,
            { pickPdfFile.launch(arrayOf("application/pdf")) },
            { kind, mime, name -> pendingKind = kind; createTarget.launch(mime to name) }
        )
    }

    private var pwDialog: AlertDialog? = null

    private fun passwordDialog() {
        if (pwDialog?.isShowing == true) return
        val et = EditText(requireContext()).apply {
            hint = "Contraseña"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = android.widget.FrameLayout(requireContext()).apply { setPadding(60, 20, 60, 0); addView(et) }
        pwDialog = AlertDialog.Builder(requireContext()).setTitle("PDF protegido").setView(box).setCancelable(false)
            .setPositiveButton("Abrir") { _, _ -> vm.retryPdf(et.text.toString()) }
            .setNegativeButton("Cancelar") { _, _ ->
                b.error.isVisible = true
                b.error.text = "PDF protegido con contraseña"
            }.show()
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        docxController = null
        xlsxController = null
        pdfController?.close(); pdfController = null
        _b = null
    }
}
