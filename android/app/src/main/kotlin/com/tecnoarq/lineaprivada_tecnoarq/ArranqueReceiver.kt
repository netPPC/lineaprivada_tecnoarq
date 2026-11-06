package com.tecnoarq.lineaprivada_tecnoarq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ArranqueReceiver - Reinicia el servicio de filtro tras el encendido del
 * equipo, si la app está activa (no desactivada). Funciona en Android,
 * HyperOS y EMUI.
 */
class ArranqueReceiver : BroadcastReceiver() {

    private val TAG = "TecnoarqBoot"

    override fun onReceive(context: Context, intent: Intent) {
        val accion = intent.action ?: return
        val esBoot = accion == Intent.ACTION_BOOT_COMPLETED ||
            accion == "android.intent.action.QUICKBOOT_POWERON" ||
            accion == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!esBoot) return

        val prefs = context.getSharedPreferences(
            "FlutterSharedPreferences", Context.MODE_PRIVATE
        )
        val modo = prefs.getString("flutter.modo_operacion", "automatico") ?: "automatico"

        if (modo == "desactivado") {
            Log.i(TAG, "🔌 App desactivada. No se reinicia el filtro tras el arranque.")
            return
        }

        Log.i(TAG, "🚀 [Boot] Reiniciando filtro Linea Privada tras encendido.")
        try {
            FiltroServicio.iniciar(context)
        } catch (e: Exception) {
            // Android 12+ restringe arrancar servicios en primer plano desde
            // segundo plano (ForegroundServiceStartNotAllowedException).
            Log.w(TAG, "⚠️ [Boot] No se pudo reiniciar el servicio: ${e.message}")
        }
    }
}
