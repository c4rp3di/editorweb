package com.docreader.editor.ui.viewer

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.data.engine.SheetModel
import com.docreader.editor.data.engine.XlsxUtil
import com.docreader.editor.databinding.ItemCellBinding

class CellAdapter(
    private val sheet: SheetModel,
    private val onClick: (ref: String, position: Int) -> Unit
) : RecyclerView.Adapter<CellAdapter.VH>() {

    class VH(val b: ItemCellBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount() = sheet.rows * sheet.cols

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCellBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val ref = XlsxUtil.colName(position % sheet.cols) + (position / sheet.cols + 1)
        h.b.cell.text = sheet.get(ref)
        h.b.cell.setTypeface(null, if (sheet.edits.containsKey(ref)) Typeface.BOLD else Typeface.NORMAL)
        h.b.root.setOnClickListener { onClick(ref, position) }
    }
}
