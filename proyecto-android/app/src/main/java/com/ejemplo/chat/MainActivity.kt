package com.ejemplo.chat

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ejemplo.chat.AppLogic
import com.ejemplo.chat.ia.MotorIA
import android.content.Intent
import com.ejemplo.chat.ui.ChatActivity
// [IMPORTS:FUNCIONALIDADES]

class MainActivity : AppCompatActivity() {

    lateinit var motorIA: MotorIA

    // [PROPIEDADES:FUNCIONALIDADES]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        motorIA = MotorIA(this)

        // [ONCREATE:FUNCIONALIDADES]
        setContentView(R.layout.activity_main)
        abrirChat()
        finish()

        // [ONCREATE_FIN:FUNCIONALIDADES]
        AppLogic.onIniciar(this)
    }

    // Funcionalidad: ia-local
    // motorIA ya esta creado. Ejemplo de uso (en una corrutina, p.ej. lifecycleScope.launch):
    //   val m = MotorIA.MODELOS[0]
    //   if (!motorIA.modeloDescargado(m)) motorIA.descargarModelo(m) { p -> /* 0..100 */ }
    //   motorIA.inicializar(m)
    //   motorIA.generar("Hola").collect { trozo -> /* texto en streaming */ }
    override fun onDestroy() {
        if (::motorIA.isInitialized) motorIA.liberar()
        super.onDestroy()
    }

    // Funcionalidad: chat-local
    // Abre la pantalla de chat. Llamala desde un boton, menu o barra.
    fun abrirChat() {
        startActivity(Intent(this, ChatActivity::class.java))
    }

    // [METODOS:FUNCIONALIDADES]
}
