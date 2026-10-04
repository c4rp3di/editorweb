package com.carpe.espejoslocos

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat

object AppLogic {

    private var imageView: ImageView? = null
    private var inicializado = false
    private var handler: Handler? = null

    fun onIniciar(activity: MainActivity) {
        LogEspejos.instalarCapturaDeCrashes()
        LogEspejos.i("onIniciar() llamado")

        ConfigCamara.init(activity)

        LogEspejos.i(
            "Configuración: ${ConfigCamara.resolucion.etiqueta} · " +
                "fps=${ConfigCamara.fps ?: "auto"} · " +
                "interp=${ConfigCamara.etiquetaInterpolacion()} · " +
                "espejar=${ConfigCamara.espejarFrontal} · " +
                "mostrarFps=${ConfigCamara.mostrarFps}"
        )

        activity.window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        activity.findViewById<View>(
            R.id.vistaPreviaCamara
        )?.visibility = View.GONE

        handler = Handler(Looper.getMainLooper())

        var intentos = 0

        val runnable = object : Runnable {
            override fun run() {

                val concedido =
                    ContextCompat.checkSelfPermission(
                        activity,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                if (concedido) {
                    LogEspejos.i(
                        "Permiso de cámara concedido " +
                            "(tras $intentos intento/s)"
                    )

                    if (!inicializado) {
                        handler?.postDelayed({
                            if (!inicializado) {
                                montarMotor(activity)
                            }
                        }, 900)
                    }
                    return
                }

                if (intentos++ < 60) {
                    handler?.postDelayed(
                        this,
                        500
                    )
                } else {
                    LogEspejos.e(
                        "Timeout esperando el permiso de cámara"
                    )
                    Toast.makeText(
                        activity,
                        "Sin permiso de cámara",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        handler?.post(runnable)
    }

    private fun montarMotor(activity: MainActivity) {
        if (inicializado) return

        inicializado = true
        LogEspejos.i("montarMotor()")

        val raiz =
            activity.findViewById<ViewGroup>(
                android.R.id.content
            )

        val iv =
            ImageView(activity).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                scaleType =
                    ImageView.ScaleType.CENTER_CROP
            }

        imageView = iv
        raiz.addView(iv)

        val ui =
            OverlayUI.crear(
                activity = activity,
                onCambiarFiltro = { filtro ->
                    LogEspejos.i(
                        "Filtro → ${filtro.nombre}"
                    )
                    Toast.makeText(
                        activity,
                        "${filtro.icono}  ${filtro.nombre}",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onCapturar = {
                    capturar(activity)
                },
                onCambiarCamara = {
                    if (MotorCamara.estaGrabando) {
                        Toast.makeText(
                            activity,
                            "Detén la grabación antes de cambiar de cámara",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        MotorCamara.alternarCamara(
                            activity,
                            iv
                        )
                        Toast.makeText(
                            activity,
                            if (MotorCamara.usandoFrontal)
                                "Frontal"
                            else
                                "Trasera",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onReiniciarCamara = {
                    if (MotorCamara.estaGrabando) {
                        Toast.makeText(
                            activity,
                            "Detén la grabación antes de cambiar la configuración",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        MotorCamara.reiniciar(
                            activity,
                            iv
                        )
                    }
                },
                onAlternarGrabacion = {
                    if (MotorCamara.estaGrabando) {
                        MotorCamara.detenerGrabacion()
                    } else {
                        MotorCamara.iniciarGrabacion(
                            activity,
                            ConfigCamara.grabarConAudio
                        )
                    }
                },
                onCambiarAudio = { activar ->
                    if (MotorCamara.estaGrabando) {
                        Toast.makeText(
                            activity,
                            "Detén la grabación antes de cambiar el audio",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (!activar) {
                        ConfigCamara.grabarConAudio = false
                    } else {
                        activity.solicitarPermisoAudio { concedido ->
                            if (concedido) {
                                ConfigCamara.grabarConAudio = true
                                Toast.makeText(
                                    activity,
                                    "El próximo vídeo incluirá el micrófono",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                ConfigCamara.grabarConAudio = false
                                Toast.makeText(
                                    activity,
                                    "Sin permiso de micrófono: se grabará sin audio",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            )

        raiz.addView(ui)

        MotorCamara.iniciar(
            activity,
            iv
        )
    }

    private fun capturar(activity: MainActivity) {
        val iv =
            imageView ?: run {
                LogEspejos.w(
                    "Captura ignorada: sin ImageView"
                )
                return
            }

        val drawable =
            iv.drawable as? BitmapDrawable ?: run {
                LogEspejos.w(
                    "Captura ignorada: sin frame"
                )
                Toast.makeText(
                    activity,
                    "Sin frame todavía",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

        val bitmap =
            drawable.bitmap ?: return

        val nombre =
            "espejo_${System.currentTimeMillis()}.jpg"

        val valores =
            ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    nombre
                )
                put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/EspejosLocos"
                    )
                }
            }

        val uri =
            activity.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                valores
            ) ?: run {
                LogEspejos.e(
                    "ContentResolver.insert devolvió null"
                )
                return
            }

        try {
            activity.contentResolver
                .openOutputStream(uri)
                ?.use { salida ->
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        95,
                        salida
                    )
                }

            LogEspejos.i(
                "Captura guardada: $nombre"
            )

            Toast.makeText(
                activity,
                "Guardado en Pictures/EspejosLocos",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            LogEspejos.e(
                "Error guardando captura",
                e
            )
        }
    }
}
