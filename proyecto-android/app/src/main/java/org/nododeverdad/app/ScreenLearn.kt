package org.nododeverdad.app

import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** «Aprende»: retos, quiz, glosario en dos registros y las promesas y límites de la app. */
class ScreenLearn(private val act: MainActivity) : Screen {

    private val scroll = ScrollView(act)
    private val box = act.vbox(16)
    private var reg = if (Prefs.profile(act) == 2) 1 else 0
    private var qIndex = -1
    private var qScore = 0
    private var chosen = -1
    private var quizBox: LinearLayout? = null

    override val view: View = scroll

    init {
        scroll.addView(box)
    }

    override fun refresh() {
        build()
    }

    private fun build() {
        box.removeAllViews()
        box.addView(act.text("Aprende", 22f, act.col(R.attr.nvText), true))
        box.addView(act.muted("Entender cuesta tres minutos. Lo difícil es que nadie te lo cuente.", 13f), act.lp(bottom = 12))

        // ----- Retos -----
        val done = HashSet<String>(Prefs.challengesDone(act))
        val chCard = act.card(16)
        val total = Learn.challenges.size
        var n = 0
        for (c in Learn.challenges) if (done.contains(c.id)) n++
        val hd = act.hbox()
        hd.addView(act.h2("Retos de privacidad"), act.lp(w = 0, weight = 1f))
        hd.addView(act.text("$n/$total", 16f, act.col(R.attr.nvAccent), true))
        chCard.addView(hd)
        chCard.addView(act.bar(n.toFloat() / total, act.col(R.attr.nvAccent), 8), act.lp(top = 8, bottom = 6))
        for (c in Learn.challenges) {
            val isDone = done.contains(c.id)
            val r = act.vbox()
            r.setPadding(act.dp(4), act.dp(10), act.dp(4), act.dp(10))
            val line = act.hbox()
            val mark = act.text(if (isDone) "☑" else "☐", 22f, if (isDone) act.col(R.attr.nvAccent) else act.col(R.attr.nvMuted), true)
            line.addView(mark)
            val tv = act.vbox()
            tv.addView(act.text(c.title, 15f, act.col(R.attr.nvText), true))
            tv.addView(act.muted(c.detail, 12f))
            line.addView(tv, act.lp(w = 0, weight = 1f, start = 12))
            r.addView(line)
            r.foreground = act.ripple(12)
            r.isClickable = true
            r.isFocusable = true
            r.contentDescription = c.title + (if (isDone) ". Hecho" else ". Pendiente") + ". Toca para cambiar."
            r.setOnClickListener {
                val cur = HashSet<String>(Prefs.challengesDone(act))
                if (cur.contains(c.id)) cur.remove(c.id) else cur.add(c.id)
                Prefs.setChallengesDone(act, cur)
                build()
            }
            chCard.addView(r)
            if (!isDone) {
                when (c.id) {
                    "ad_id" -> chCard.addView(act.button("Abrir ajustes de privacidad", false) {
                        try {
                            act.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS))
                        } catch (e: Exception) {
                            act.toast("Abre Ajustes → Privacidad → Anuncios.")
                        }
                    }, act.lp(bottom = 4))
                    "parent" -> chCard.addView(act.button("Ir a «Quién»", false) { act.goTo(2) }, act.lp(bottom = 4))
                    "share" -> chCard.addView(act.button("Compartir mi radar", false) { act.shareRadar() }, act.lp(bottom = 4))
                    else -> {}
                }
            }
        }
        box.addView(chCard)

        // ----- Quiz -----
        val qb = act.card(16)
        quizBox = qb
        box.addView(qb, act.lp(top = 12))
        renderQuiz()

        // ----- Glosario -----
        box.addView(act.h2("Glosario sin jerga"), act.lp(top = 22))
        box.addView(act.segmented(listOf("Como si tuviera 15", "Como si tuviera 70"), reg) {
            reg = it
            build()
        }, act.lp(top = 8))
        for (t in Learn.glossary) {
            val c = act.card(14)
            c.addView(act.text(t.name, 16f, act.col(R.attr.nvAccent), true))
            c.addView(act.text(if (reg == 0) t.t15 else t.t70, 15f), act.lp(top = 4))
            box.addView(c, act.lp(top = 8))
        }

        // ----- Promesas y límites -----
        box.addView(act.h2("Lo que esta app promete"), act.lp(top = 22))
        val pc = act.card(16)
        pc.addView(act.text("• Nada sale de tu móvil: no hay cuentas ni servidores nuestros.\n• No guardamos tu historial: solo anotamos las consultas a rastreadores conocidos; del resto solo cuántas hay.\n• No leemos el contenido: solo el nombre del servidor al que llama cada app.\n• Sin anuncios, sin analítica, sin venta de datos.\n• Todo se borra con un toque.", 14f))
        box.addView(pc, act.lp(top = 8))

        box.addView(act.h2("Y lo que NO puede hacer"), act.lp(top = 18))
        val lc = act.card(16)
        lc.addView(act.text("• No ve apps que usan DNS cifrado propio o se conectan por IP directa.\n• Si tienes «DNS privado» en modo estricto (con nombre de proveedor), esas consultas no pasan por el radar. Ponlo en Automático.\n• La atribución a cada app es aproximada y requiere Android 10 o superior.\n• La base de empresas es una lista curada v1 que puede tener errores u omisiones. Aparecer en ella no implica ilegalidad.\n• No prueba intención: recibir señales no es lo mismo que hacer algo malo.\n• No sustituye al periodismo de investigación ni a la justicia.", 14f))
        box.addView(lc, act.lp(top = 8))

        // ----- Datos -----
        box.addView(act.h2("Tus datos, tuyos"), act.lp(top = 18))
        box.addView(act.button("Exportar (JSON abierto)", false) { act.exportData() }, act.lp(top = 8))
        box.addView(act.space(16))
    }

    private fun renderQuiz() {
        val qb = quizBox ?: return
        qb.removeAllViews()
        qb.addView(act.h2("¿Cuánto sabes?"))
        val qs = Learn.quiz
        if (qIndex < 0) {
            val best = Prefs.quizBest(act)
            qb.addView(act.text(qs.size.toString() + " preguntas rápidas." + (if (best > 0) " Tu mejor marca: $best/${qs.size}." else ""), 15f), act.lp(top = 6))
            qb.addView(act.button("Empezar", true) {
                qIndex = 0
                qScore = 0
                chosen = -1
                renderQuiz()
            }, act.lp(top = 12))
            return
        }
        if (qIndex >= qs.size) {
            if (qScore > Prefs.quizBest(act)) Prefs.setQuizBest(act, qScore)
            val msg = if (qScore == qs.size) "¡Perfecto! Ya sabes más que la mayoría." else if (qScore >= qs.size / 2) "Muy bien. Repasa el glosario y lo tendrás." else "Buen comienzo: el glosario de abajo te ayuda."
            qb.addView(act.text("Has acertado $qScore de ${qs.size}", 20f, act.col(R.attr.nvAccent), true), act.lp(top = 8))
            qb.addView(act.text(msg, 15f), act.lp(top = 4))
            qb.addView(act.button("Repetir", false) {
                qIndex = 0
                qScore = 0
                chosen = -1
                renderQuiz()
            }, act.lp(top = 12))
            return
        }
        val q = qs[qIndex]
        qb.addView(act.muted("Pregunta ${qIndex + 1} de ${qs.size}", 12f), act.lp(top = 4))
        qb.addView(act.text(q.q, 17f, act.col(R.attr.nvText), true), act.lp(top = 6))
        if (chosen < 0) {
            for (i in q.options.indices) {
                qb.addView(act.button(q.options[i], false) {
                    chosen = i
                    if (i == q.correct) qScore++
                    renderQuiz()
                }, act.lp(top = 8))
            }
        } else {
            val ok = chosen == q.correct
            val c = if (ok) act.col(R.attr.nvPush) else act.col(R.attr.nvAtr)
            qb.addView(act.text(if (ok) "✔ Correcto" else "✘ No exactamente", 16f, c, true), act.lp(top = 10))
            qb.addView(act.text("Respuesta: " + q.options[q.correct], 14f, act.col(R.attr.nvText), true), act.lp(top = 4))
            qb.addView(act.text(q.why, 14f), act.lp(top = 6))
            qb.addView(act.button(if (qIndex == qs.size - 1) "Ver resultado" else "Siguiente", true) {
                qIndex++
                chosen = -1
                renderQuiz()
            }, act.lp(top = 12))
        }
    }
}
