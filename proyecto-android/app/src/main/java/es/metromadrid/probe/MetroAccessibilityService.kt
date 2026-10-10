package es.metromadrid.probe

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lee solo el árbol accesible de Metro. No realiza capturas de imagen. */
class MetroAccessibilityService : AccessibilityService() {
    companion object {
        private const val TARGET_PACKAGE = "es.metromadrid.metroandroid"
        private const val MAX_NODES = 220
        private const val MAX_CHARS = 26000
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != TARGET_PACKAGE) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != TARGET_PACKAGE) return
            val out = StringBuilder(4096)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
            out.append("METRO ACCESSIBILITY PROBE\n")
                .append("Lectura local: ").append(stamp).append('\n')
                .append("Paquete: ").append(TARGET_PACKAGE).append('\n')
                .append("Evento: ").append(eventTypeName(event.eventType)).append('\n')
                .append("NOTA: árbol de accesibilidad, no es una captura de imagen.\n\n")
            walk(root, 0, intArrayOf(0), out)
            ProbeState.store(out.toString(), System.currentTimeMillis())
        } catch (_: Exception) {
            // Conserva la última lectura válida si la interfaz cambia durante el recorrido.
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, counter: IntArray, out: StringBuilder) {
        if (node == null || counter[0] >= MAX_NODES || out.length >= MAX_CHARS) return
        counter[0]++
        repeat(depth.coerceAtMost(16)) { out.append("  ") }
        out.append('[').append(counter[0]).append("] ").append(safe(node.className, 100))
        node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { out.append(" id=").append(safe(it, 180)) }
        node.text?.takeIf { it.isNotEmpty() }?.let { out.append(" text=\"").append(safe(it, 220)).append('"') }
        node.contentDescription?.takeIf { it.isNotEmpty() }?.let { out.append(" desc=\"").append(safe(it, 220)).append('"') }
        if (node.isClickable) out.append(" clickable")
        if (node.isScrollable) out.append(" scrollable")
        if (node.isEditable) out.append(" editable")
        out.append('\n')
        for (i in 0 until node.childCount) {
            if (counter[0] >= MAX_NODES || out.length >= MAX_CHARS) break
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            try { if (child != null) walk(child, depth + 1, counter, out) }
            catch (_: Exception) { }
            finally { if (child != null) { @Suppress("DEPRECATION") child.recycle() } }
        }
        if (counter[0] >= MAX_NODES || out.length >= MAX_CHARS) {
            repeat((depth + 1).coerceAtMost(16)) { out.append("  ") }
            out.append("… árbol limitado por la sonda …\n")
        }
    }

    private fun safe(value: CharSequence?, max: Int): String = (value?.toString() ?: "")
        .replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replace('"', '\'')
        .let { if (it.length > max) it.substring(0, max) + "…" else it }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        else -> "TYPE_$type"
    }

    override fun onInterrupt() = Unit
}
