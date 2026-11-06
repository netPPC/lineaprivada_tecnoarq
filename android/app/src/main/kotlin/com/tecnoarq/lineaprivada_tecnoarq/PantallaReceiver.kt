package com.tecnoarq.lineaprivada_tecnoarq

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * PantallaReceiver - Notifica las intercepciones recientes al desbloquear la
 * pantalla del celular.
 *
 * Al desbloquear (ACTION_USER_PRESENT), si hay un bloqueo automático pendiente
 * de revisar, se muestra una notificación heads-up (categoría llamada) que al
 * tocarla abre la app y su popup de "Intercepción Reciente".
 *
 * Es best-effort en Android 10+: algunas capas (HyperOS/EMUI) pueden retrasar
 * o silenciar el heads-up; la app siempre lo mostrará al abrirla.
 */
class PantallaReceiver : BroadcastReceiver() {

    private val TAG = "TecnoarqPantalla"
    private val CHANNEL_ID = "linea_privada_intercepcion"
    private val NOTIF_ID = 5002

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return

        val prefs = context.getSharedPreferences(
            "FlutterSharedPreferences", Context.MODE_PRIVATE
        )
        val numero = prefs.getString("flutter.ultimo_numero_bloqueado_automatico", "") ?: ""
        if (numero.isEmpty()) return

        // Evitar notificar el mismo bloqueo en cada desbloqueo
        val yaNotificado = prefs.getString("flutter.notif_unlock_$numero", "") == "1"
        if (yaNotificado) return
        prefs.edit().putString("flutter.notif_unlock_$numero", "1").apply()

        val motivo = prefs.getString(
            "flutter.ultimo_motivo_bloqueo", "Call Center / Spam"
        ) ?: "Call Center / Spam"

        mostrarNotificacionIntercepcion(context, numero, motivo)
    }

    private fun mostrarNotificacionIntercepcion(
        context: Context,
        numero: String,
        motivo: String
    ) {
        try {
            val intentApp = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                context, 0, intentApp,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val canal = NotificationChannel(
                    CHANNEL_ID,
                    "Intercepciones Linea Privada",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Aviso de llamadas bloqueadas al desbloquear la pantalla."
                    setShowBadge(false)
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
                manager.createNotificationChannel(canal)
            }

            val notificacion = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("🛡️ Intercepción Reciente")
                .setContentText("Llamada bloqueada de $numero · $motivo")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            manager.notify(NOTIF_ID, notificacion)
            Log.i(TAG, "🔔 Notificación de intercepción mostrada ($numero).")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ No se pudo mostrar la notificación de intercepción: ${e.message}")
        }
    }
}
