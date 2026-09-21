package com.carpe.microlisto

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.carpe.microlisto.databinding.ActivityMainBinding
import com.carpe.microlisto.ui.AjustesFragment
import com.carpe.microlisto.ui.DetalleFragment
import com.carpe.microlisto.ui.GrabarFragment
import com.carpe.microlisto.ui.HistorialFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppLogic.onIniciar(this)

        if (savedInstanceState == null) {
            mostrarFragment(GrabarFragment())
        }

        binding.barraInferior.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_grabar -> mostrarFragment(GrabarFragment())
                R.id.nav_historial -> mostrarFragment(HistorialFragment())
                R.id.nav_detalle -> mostrarFragment(DetalleFragment())
                R.id.nav_ajustes -> mostrarFragment(AjustesFragment())
                else -> return@setOnItemSelectedListener false
            }
            true
        }
    }

    fun mostrarFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedor_fragments, fragment)
            .commit()
    }
}