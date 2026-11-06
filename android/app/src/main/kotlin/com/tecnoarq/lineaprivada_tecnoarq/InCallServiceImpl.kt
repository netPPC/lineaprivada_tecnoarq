package com.tecnoarq.lineaprivada_tecnoarq

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * InCallServiceImpl - Pantalla de llamada propia (rol de teléfono por defecto).
 *
 * Cuando la app es el dialer por defecto, Android enlaza este servicio para
 * TODAS las llamadas. Esto permite:
 *   - Mostrar nuestra propia pantalla de llamada (número + motivo + botones).
 *   - Colgar las llamadas de desconocidos de forma NATIVA al vencer el tope de
 *     5 tonadas (call.disconnect()), sin necesitar root ni accesibilidad.
 *   - Responder/rechazar llamadas entrantes desde nuestra UI.
 *
 * Las llamadas salientes se mantienen y solo se muestran con un botón "Colgar";
 * la conexión telefónica la gestiona el sistema/carrier.
 */
class InCallServiceImpl : InCallService() {

    companion object {
        private const val TAG = "TecnoarqInCall"

        private const val CHANNEL_LLAMADA = "linea_privada_llamada"
        private const val NOTIF_ID_LLAMADA = 5002
        private const val NOTIF_ID_BANNER = 5003

        /** ~5 tonos: unos 4s por ring (medido en red chilena). */
        private const val MILIS_AUTOCOLGADO = 20000L

        @Volatile
        var llamadaActual: Call? = null
            private set

        @Volatile
        var motivoActual: String = ""
            private set

        @Volatile
        var esDesconocidaActual: Boolean = false
            private set

        @Volatile
        var instanciaServicio: InCallServiceImpl? = null
            private set

        /** Cuelga la llamada actual (desde la UI). */
        fun colgarLlamada() {
            llamadaActual?.let { call ->
                try {
                    call.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [InCall] Error al colgar: ${e.message}")
                }
            }
        }

        /** true si la llamada en curso es saliente (no se aplica tope de ring). */
        fun hayLlamadaSaliente(): Boolean {
            return llamadaActual?.details?.callDirection == Call.Details.DIRECTION_OUTGOING
        }

        /** Envía un tono DTMF durante una llamada activa (menús de atención). */
        fun enviarDtmf(digito: Char) {
            llamadaActual?.let { call ->
                try {
                    call.playDtmfTone(digito)
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            call.stopDtmfTone()
                        } catch (ignored: Exception) {
                        }
                    }, 250)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [InCall] Error al enviar DTMF $digito: ${e.message}")
                }
            }
        }

        /** Alterna el altavoz (manos libres). Devuelve el nuevo estado. */
        fun alternarAltavoz(): Boolean {
            val service = instanciaServicio
            if (service == null) {
                Log.e(TAG, "❌ [InCall] Sin servicio InCall para altavoz.")
                return false
            }
            return try {
                val estado = service.callAudioState
                val nuevo = estado?.route != CallAudioState.ROUTE_SPEAKER
                service.setAudioRoute(
                    if (nuevo) CallAudioState.ROUTE_SPEAKER
                    else CallAudioState.ROUTE_EARPIECE
                )
                nuevo
            } catch (e: Exception) {
                Log.e(TAG, "❌ [InCall] Error al alternar altavoz: ${e.message}")
                false
            }
        }

        /** Alterna el micrófono en silencio. Devuelve el nuevo estado (true=silenciado). */
        fun alternarSilencio(): Boolean {
            val service = instanciaServicio
            if (service == null) return false
            return try {
                val estado = service.callAudioState
                val nuevo = estado?.isMuted != true
                service.setMuted(nuevo)
                nuevo
            } catch (e: Exception) {
                Log.e(TAG, "❌ [InCall] Error al alternar silencio: ${e.message}")
                false
            }
        }

        /** Estado actual del altavoz (para refrescar la UI). */
        fun altavozActivoActual(): Boolean {
            return instanciaServicio?.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
        }

        /** Estado actual de silencio. */
        fun silencioActivoActual(): Boolean {
            return instanciaServicio?.callAudioState?.isMuted ?: false
        }

        /** Responde la llamada entrante (audio). */
        fun responderLlamada() {
            llamadaActual?.let { call ->
                try {
                    call.answer(0)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [InCall] Error al responder: ${e.message}")
                }
            }
        }

        /** Rechaza la llamada entrante. */
        fun rechazarLlamada() {
            llamadaActual?.let { call ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        call.reject(Call.REJECT_REASON_DECLINED)
                    } else {
                        // reject(int) solo existe en API 31+; en API 29-30 se
                        // cuelga la llamada sonando (queda como perdida).
                        call.disconnect()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [InCall] Error al rechazar: ${e.message}")
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var runnableAutoColgado: Runnable? = null
    private var numeroActual = ""
    private var fueRespondida = false
    private var topeAplicado = false

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.i(TAG, "📞 [InCall] Estado $state para ${call.details.handle?.schemeSpecificPart}")
            when (state) {
                Call.STATE_ACTIVE -> {
                    fueRespondida = true
                    cancelarAutoColgado()
                }
                Call.STATE_CONNECTING,
                Call.STATE_DIALING -> cancelarAutoColgado()
                Call.STATE_DISCONNECTED -> {
                    cancelarAutoColgado()
                    cerrarPantalla()
                    // Llamada desconocida que se perdió (el que llama colgó antes
                    // de las 5 tonadas o no se contestó en modo desatendido):
                    // registrar la intercepción para que el aviso de llamada
                    // perdida aparezca al abrir la app o desbloquear la pantalla.
                    if (esDesconocidaActual && !fueRespondida && !topeAplicado) {
                        registrarIntercepcion(
                            numeroActual,
                            "Llamada perdida de desconocido"
                        )
                    }
                }
            }
            PantallaLlamadaActivity.instancia?.actualizar()
            DialerActivity.notificarActualizacion()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            super.onDetailsChanged(call, details)
            PantallaLlamadaActivity.instancia?.actualizar()
            DialerActivity.notificarActualizacion()
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        instanciaServicio = this
        llamadaActual = call
        call.registerCallback(callback)

        val numero = call.details.handle?.schemeSpecificPart ?: ""
        val entrante = call.details.callDirection == Call.Details.DIRECTION_INCOMING
        Log.i(TAG, "📞 [InCall] Llamada ${if (entrante) "entrante" else "saliente"}: $numero")

        esDesconocidaActual = entrante && esDesconocido(numero)
        numeroActual = numero
        fueRespondida = false
        topeAplicado = false
        motivoActual = if (esDesconocidaActual) {
            "Llamada desconocida — se cortará en ~5 tonos"
        } else {
            "Llamada en curso"
        }

        if (esDesconocidaActual) {
            programarAutoColgado(numero)
        }

        // Llamada saliente: si el marcador ya está visible en primer plano, no se
        // abre PantallaLlamadaActivity encima: el teclado numérico y el número
        // en el campo quedan visibles durante toda la llamada saliente.
        val salienteConMarcador = !entrante && DialerActivity.instanciaVisible()
        if (salienteConMarcador) {
            Log.i(TAG, "📲 [InCall] Saliente con marcador visible: se mantiene el marcador.")
            DialerActivity.notificarActualizacion()
        } else {
            abrirPantalla(numero)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callback)
        if (llamadaActual === call) {
            llamadaActual = null
            numeroActual = ""
            fueRespondida = false
            topeAplicado = false
            cancelarAutoColgado()
            cerrarPantalla()
        }
    }

    override fun onDestroy() {
        cancelarAutoColgado()
        llamadaActual = null
        if (instanciaServicio === this) instanciaServicio = null
        super.onDestroy()
    }

    private fun esDesconocido(numero: String): Boolean {
        if (numero.isEmpty()) return false
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val modo = prefs.getString("flutter.modo_operacion", "automatico")
            ?: "automatico"
        if (modo == "desactivado") return false
        if (prefs.getString("flutter.bloquear_desconocidos", "true") != "true") {
            return false
        }
        if (MainActivity.listaBlancaTemporal.contains(numero)) return false
        val digitos = FiltroNucleo.soloDigitos(numero)
        if (prefs.getBoolean("flutter.permitido_$digitos", false)) return false
        if (ContactosHelper.existeContacto(this, numero)) return false
        return true
    }

    private fun programarAutoColgado(numero: String) {
        cancelarAutoColgado()
        runnableAutoColgado = Runnable {
            Log.i(TAG, "⏰ [InCall] Tope de 5 tonadas vencido. Colgando: $numero")
            topeAplicado = true
            try {
                llamadaActual?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ [InCall] Error al colgar: ${e.message}")
            } finally {
                // Siempre registrar la intercepción, aunque el colgado falle.
                registrarIntercepcion(numero, "Tope de ring (5 tonadas)")
            }
        }
        handler.postDelayed(runnableAutoColgado!!, MILIS_AUTOCOLGADO)
    }

    private fun cancelarAutoColgado() {
        runnableAutoColgado?.let { handler.removeCallbacks(it) }
        runnableAutoColgado = null
    }

    /** Registra la intercepción para el popup de la app y la notificación al desbloquear. */
    private fun registrarIntercepcion(numero: String, motivo: String) {
        try {
            val limpio = FiltroNucleo.normalizar(numero)
            if (limpio.isEmpty()) return
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("flutter.ultimo_numero_bloqueado_automatico", limpio)
                .putString("flutter.ultimo_motivo_bloqueo", motivo)
                .remove("flutter.popup_mostrado_$limpio")
                .remove("flutter.notif_unlock_$limpio")
                .apply()
            Log.i(TAG, "🔔 [InCall] Intercepción registrada para notificación: $limpio -> $motivo")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ No se pudo registrar la intercepción: ${e.message}")
        }
    }

    private fun abrirPantalla(numero: String) {
        // La vía estándar que usan los dialers de fábrica para despertar la
        // pantalla y mostrar la UI de llamada SOBRE EL BLOQUEO es el full-screen
        // intent de una notificación IMPORTANCE_HIGH (el startActivity directo
        // sobre el keyguard lo deniega MIUI en silencio con "Permission Denied
        // Activity KeyguardLocked").
        //
        // PERO cuando la pantalla ya está encendida y desbloqueada (la app está
        // en primer plano, p. ej. el usuario marca desde nuestro dialer), MIUI
        // NO auto-lanza el full-screen intent: solo publica el heads-up y la
        // pantalla de llamada nunca se abre. En ese caso hay que lanzar la
        // actividad con startActivity directo.
        val intent = Intent(this, PantallaLlamadaActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pantallaEncendida = pm.isInteractive
        val keyguardDesbloqueado = !km.isKeyguardLocked

        if (pantallaEncendida && keyguardDesbloqueado) {
            try {
                startActivity(intent)
                Log.i(TAG, "📲 [InCall] Pantalla abierta por startActivity directo (primer plano).")
                return
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ [InCall] startActivity directo falló: ${e.message}")
            }
        }

        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_LLAMADA,
                        "Llamada Linea Privada",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
            }
            val pend = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val titulo = if (esDesconocidaActual) {
                "Llamada desconocida entrante"
            } else {
                "Llamada entrante"
            }
            val texto = if (numero.isNotEmpty()) {
                "$numero — se cortará en ~5 tonos"
            } else {
                "Se cortará en ~5 tonos"
            }
            val notificacion = NotificationCompat.Builder(this, CHANNEL_LLAMADA)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pend, true)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID_LLAMADA, notificacion)
            Log.i(TAG, "📲 [InCall] Pantalla lanzada por full-screen intent.")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ [InCall] FSI falló: ${e.message}")
            // Respaldo: startActivity directo (funciona con pantalla desbloqueada).
            try {
                startActivity(intent)
                Log.i(TAG, "📲 [InCall] Pantalla abierta por startActivity directo (respaldo).")
            } catch (e2: Exception) {
                Log.e(TAG, "⚠️ [InCall] startActivity de respaldo falló: ${e2.message}")
            }
        }
    }

    private fun cerrarPantalla() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID_LLAMADA)
            nm.cancel(NOTIF_ID_BANNER)
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ [InCall] No se pudo cancelar la notificación de llamada: ${e.message}")
        }
        PantallaLlamadaActivity.instancia?.let { it.finish() }
    }
}
