package com.tecnoarq.lineaprivada_tecnoarq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Telephony
import android.util.Log

/**
 * SmsReceiver - Filtro nativo de mensajes SMS.
 *
 * Intercepta SMS_RECEIVED, aplica las reglas de FiltroNucleo y:
 *  - Permite los SMS de contactos y números confiables.
 *  - Filtra (registra + intenta eliminar) los SMS de spam, desconocidos,
 *    internacionales, códigos cortos o números de la base comunitaria.
 *  - Guarda los filtrados para revisión en la interfaz.
 *  - Avisa a Flutter en tiempo real para que la UI se refresque.
 */
class SmsReceiver : BroadcastReceiver() {

    private val TAG = "TecnoarqSms"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val mensajes = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?: return

        val prefs = context.getSharedPreferences(
            "FlutterSharedPreferences", Context.MODE_PRIVATE
        )

        // Agrupar por remitente para no duplicar el registro por cada parte
        val porRemitente = LinkedHashMap<String, String>()
        for (sms in mensajes) {
            val remitente = sms.displayOriginatingAddress ?: ""
            val cuerpo = porRemitente[remitente] ?: ""
            porRemitente[remitente] = (cuerpo + " " + sms.messageBody).trim()
        }

        for ((remitente, cuerpo) in porRemitente) {
            procesarSms(context, prefs, remitente, cuerpo)
        }
    }

    private fun procesarSms(
        context: Context,
        prefs: SharedPreferences,
        remitente: String,
        cuerpo: String
    ) {
        val motivo = FiltroNucleo.clasificarSms(context, prefs, remitente, cuerpo)

        if (motivo == null) {
            Log.i(TAG, "✅ [SMS] Permitido: $remitente")
            return
        }

        Log.w(TAG, "🚫 [SMS] Filtrado: $remitente -> $motivo")

        // Registrar en la lista persistente de la interfaz
        FiltroNucleo.guardarSmsFiltrado(prefs, remitente, cuerpo, motivo)

        // Regla del usuario: el SMS se filtra bajo las MISMAS reglas que los
        // números, por lo que el remitente también queda registrado como
        // bloqueado (aparece en "Números Bloqueados" y dispara el mismo popup
        // de inicio que las llamadas).
        FiltroNucleo.registrarBloqueo(prefs, remitente, motivo)

        // Intento de eliminación best-effort del buzón (requiere permisos o app SMS por defecto)
        intentarEliminarDelBuzon(context, remitente, cuerpo)

        // Refresco en vivo de la interfaz Flutter
        MainActivity.notificarSmsFiltrado("SMS", remitente, motivo)
    }

    private fun intentarEliminarDelBuzon(context: Context, remitente: String, cuerpo: String) {
        try {
            val uriSms = Uri.parse("content://sms")
            val cursor = context.contentResolver.query(
                uriSms,
                arrayOf("_id"),
                "address LIKE ? AND body LIKE ?",
                arrayOf("%$remitente%", "%${cuerpo.take(40)}%"),
                null
            )
            val ids = ArrayList<Long>()
            cursor?.use {
                while (it.moveToNext()) {
                    ids.add(it.getLong(0))
                }
            }
            for (id in ids) {
                context.contentResolver.delete(
                    Uri.withAppendedPath(uriSms, id.toString()),
                    null,
                    null
                )
            }
            if (ids.isNotEmpty()) {
                Log.i(TAG, "🗑️ [SMS] Eliminados ${ids.size} SMS del buzón.")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "ℹ️ [SMS] Sin permiso para eliminar del buzón (solo registrado).")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ [SMS] Error eliminando del buzón: ${e.message}")
        }
    }
}
