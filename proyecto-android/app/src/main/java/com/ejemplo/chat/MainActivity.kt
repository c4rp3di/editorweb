package com.ejemplo.chat

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ejemplo.chat.AppLogic
import com.ejemplo.chat.ia.MotorIA
import android.content.Intent
import com.ejemplo.chat.ui.ChatActivity

class MainActivity : AppCompatActivity() {

    lateinit var motorIA: MotorIA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        motorIA = MotorIA(this)
        setContentView(R.layout.activity_main)
        abrirChat()
        finish()
        AppLogic.onIniciar(this)
    }

    override fun onDestroy() {
        if (::motorIA.isInitialized) motorIA.liberar()
        super.onDestroy()
    }

    fun abrirChat() {
        startActivity(Intent(this, ChatActivity::class.java))
    }
}
