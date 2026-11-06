package com.tecnoarq.lineaprivada_tecnoarq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * CallReceiver - Receptor de estado telefónico para Android 9 y anteriores.
 *
 * En versiones sin CallScreeningService (API < 29) interceptamos el estado
 * PHONE_STATE y aplicamos las mismas reglas de FiltroNucleo. El bloqueo se
 * ejecuta como cuelgue inmediato (best-effort) y se registra para la base
 * de datos.
 */
class CallReceiver : BroadcastReceiver() {

    private val TAG = "TecnoarqReceiver9"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        // SOLO aplica a Android 9 (API < 29). En Android 10+ el filtrado lo hace
        // CallScreeningService; si este receptor estuviera activo, colgaría la
        // llamada con el número vacío antes de que el servicio pueda contestar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "🚫 [Android 9] Receptor deshabilitado en API >= 29. Ignorando.")
            return
        }

        val estado = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val numeroEntrante = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        if (estado != TelephonyManager.EXTRA_STATE_RINGING) return

        val prefs = context.getSharedPreferences(
            "FlutterSharedPreferences", Context.MODE_PRIVATE
        )

        Log.d(TAG, "🔔 [Android 9] Llamada entrante: $numeroEntrante")

        val resultado = FiltroNucleo.clasificarLlamada(context, prefs, numeroEntrante)

        if (resultado.motivo != null) {
            Log.w(TAG, "🛑 [Android 9] Bloqueo: $numeroEntrante -> ${resultado.motivo}")
            FiltroNucleo.registrarBloqueo(prefs, numeroEntrante, resultado.motivo!!)
            MainActivity.notificarCambioLlamada("BLOQUEADO", numeroEntrante)
            ejecutarCuelgueInmediato(context, numeroEntrante)
        } else {
            Log.i(TAG, "🟢 [Android 9] Llamada permitida: $numeroEntrante")
        }
    }

    private fun ejecutarCuelgueInmediato(context: Context, numero: String) {
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
                Log.e(TAG, "✅ [Android 9] Llamada $numero colgada.")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ [Android 9] Permiso faltante al colgar: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [Android 9] Error al colgar: ${e.message}")
        }
    }
}
