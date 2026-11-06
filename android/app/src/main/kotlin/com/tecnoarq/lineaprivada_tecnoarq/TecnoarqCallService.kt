package com.tecnoarq.lineaprivada_tecnoarq

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * TecnoarqCallService - Filtro de llamadas por Rol de Sistema (Android 10+).
 *
 * Compatible con Android 10 -> Android 16, HyperOS y EMUI.
 * Las reglas están centralizadas en FiltroNucleo para no duplicar lógica.
 *
 *  - Bloqueo inmediato: oculto, internacional, estructura extraña, lista negra
 *    comunitaria (match completo), prefijos quemados y números desconocidos.
 *  - Contactos y lista blanca: pasan sin intervención.
 */
@RequiresApi(Build.VERSION_CODES.Q)
@SuppressLint("MissingSuperCall")
class TecnoarqCallService : CallScreeningService() {

    private val TAG = "TecnoarqScreening"

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection == Call.Details.DIRECTION_OUTGOING) {
            respondWithPass(callDetails)
            return
        }

        val numeroCrudo = callDetails.handle?.schemeSpecificPart ?: ""
        val numero = FiltroNucleo.normalizar(numeroCrudo)
        val prefs = applicationContext.getSharedPreferences(
            "FlutterSharedPreferences", Context.MODE_PRIVATE
        )

        Log.i(TAG, "📞 [TecnoarQ] Evaluando llamada entrante: $numero")

        // Lista blanca en RAM (desbloqueos de esta sesión)
        if (MainActivity.listaBlancaTemporal.contains(numero)) {
            Log.i(TAG, "🔓 [TecnoarQ] $numero en lista blanca temporal. Permitiendo.")
            respondWithPass(callDetails)
            return
        }

        val resultado = FiltroNucleo.clasificarLlamada(applicationContext, prefs, numero)

        when {
            // BLOQUEO INMEDIATO (spam, desconocidos, internacionales, etc.)
            resultado.motivo != null -> {
                Log.w(TAG, "🚨 [TecnoarQ] Bloqueo: $numero -> ${resultado.motivo}")
                FiltroNucleo.registrarBloqueo(prefs, numero, resultado.motivo!!)
                MainActivity.notificarCambioLlamada("BLOQUEADO", numero)
                respondWithBlock(callDetails, numero, resultado.motivo!!)
            }

            // CONTACTO O LISTA BLANCA -> pasa sin intervención
            else -> {
                Log.i(TAG, "🟢 [TecnoarQ] Llamada legítima permitida: $numero")
                respondWithPass(callDetails)
                // Solo armar tope de ring si NO es la primera vez que llama este
                // número. En primera llamada, el usuario decide libremente sin
                // intervención automática (la llamada suena normalmente).
                if (!resultado.esPrimeraLlamada) {
                    // Si el número es un desconocido permitido (no contacto), arma el
                    // tope de ring para cortar la llamada si nadie contesta. En
                    // Android 12+ el PhoneStateListener no entrega el número, por eso
                    // el armado se hace aquí, donde sí lo tenemos.
                    FiltroServicio.armarTopeRing(this, numero)
                } else {
                    Log.i(TAG, "🆕 [TecnoarQ] Primera llamada de $numero. Sin tope de ring; el usuario decide.")
                }
            }
        }
    }

    // Rechazo directo para spam confirmado
    private fun respondWithBlock(callDetails: Call.Details, numero: String, motivo: String) {
        val response = CallScreeningService.CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)
        Log.e(TAG, "🛑 [TecnoarQ] Número $numero rechazado: $motivo")
    }

    // Permiso para llamadas legítimas
    private fun respondWithPass(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
