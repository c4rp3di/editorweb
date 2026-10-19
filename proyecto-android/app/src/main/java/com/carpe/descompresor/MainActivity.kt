package com.carpe.descompresor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.carpe.descompresor.AppLogic

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppLogic.onIniciar(this)
    }
}
