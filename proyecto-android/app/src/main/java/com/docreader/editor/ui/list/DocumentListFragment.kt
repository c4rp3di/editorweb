package com.docreader.editor.ui.list

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.docreader.editor.R
import com.docreader.editor.databinding.FragmentListBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** OpenDocument que además pide permiso de escritura persistente. */
class OpenDocWritable : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

@AndroidEntryPoint
class DocumentListFragment : Fragment() {
    private var _b: FragmentListBinding? = null
    private val b get() = _b!!
    private val vm: DocumentListViewModel by viewModels()

    private val picker = registerForActivityResult(OpenDocWritable()) { uri ->
        uri?.let { vm.import(it) }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = DocumentAdapter(
            onClick = {
                findNavController().navigate(R.id.action_list_to_viewer, bundleOf("uri" to it.uri))
            },
            onLongClick = { doc ->
                AlertDialog.Builder(requireContext())
                    .setTitle(doc.displayName)
                    .setMessage("¿Quitar de la biblioteca? (el archivo no se borra)")
                    .setPositiveButton("Quitar") { _, _ -> vm.delete(doc.uri) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.fab.setOnClickListener { picker.launch(arrayOf("*/*")) }
        b.search.doAfterTextChanged { vm.setQuery(it?.toString().orEmpty()) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.docs.collect {
                    adapter.submitList(it)
                    b.empty.isVisible = it.isEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
