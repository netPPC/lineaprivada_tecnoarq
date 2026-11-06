package com.tecnoarq.lineaprivada_tecnoarq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * FiltroServicio - Servicio en Primer Plano ligero (sin contestadora ni audio).
 *
 * Mantiene vivo el proceso para que el filtro de llamadas y SMS funcione de
 * forma DESATENDIDA (pantalla apagada) en Android, HyperOS (Xiaomi) y
 * EMUI/HarmonyOS (Huawei).
 *
 * Además aplica el "tope de ring": una llamada de número desconocido suena
 * hasta un máximo de 5 tonadas y, si nadie contesta, la app intenta colgarla
 * (best-effort; requiere MODIFY_PHONE_STATE vía módulo Magisk o ser dialer por
 * defecto, ya que Android no permite a apps de terceros colgar llamadas en
 * curso sin ese permiso).
 *
 * El armado del tope se hace desde el CallScreeningService (que conoce el
 * número real incluso en Android 12+, donde PhoneStateListener entrega el
 * número vacío por privacidad) y también desde PhoneStateListener como respaldo
 * en Android 9-11 y para cancelar el timer al contestar o terminar la llamada.
 */
class FiltroServicio : Service() {

    companion object {
        private const val TAG = "TecnoarqServicio"
        private const val CHANNEL_ID = "linea_privada_filtro"
        private const val NOTIF_ID = 4001

        /** ~5 tonos: unos 4s por ring (medido en red chilena). */
        private const val MILIS_TOPE_RING = 20000L

        @Volatile
        var activo: Boolean = false
            private set

        @Volatile
        private var contexto: Context? = null

        @Volatile
        private var instancia: FiltroServicio? = null

        private val handler = Handler(Looper.getMainLooper())

        @Volatile
        private var runnableTope: Runnable? = null

        @Volatile
        private var numeroEnTope = ""

        fun iniciar(context: Context) {
            try {
                val intent = Intent(context, FiltroServicio::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ No se pudo iniciar el servicio: ${e.message}")
            }
        }

        fun detener(context: Context) {
            try {
                context.stopService(Intent(context, FiltroServicio::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ No se pudo detener el servicio: ${e.message}")
            }
        }

        /**
         * Arma el tope de ring para una llamada permitida. Lo invoca el
         * CallScreeningService con el número real. Re-valida que sea un
         * desconocido filtrable (no contacto, no lista blanca, no permitido).
         * NUNCA se aplica a llamadas salientes (líneas de atención al cliente,
         * marcaciones del usuario): solo a llamadas entrantes.
         */
        fun armarTopeRing(context: Context, numero: String) {
            val c = contexto ?: context.applicationContext
            contexto = c
            cancelarTope()
            // Llamada saliente en curso (la marca el usuario): jamás se corta.
            if (InCallServiceImpl.hayLlamadaSaliente()) {
                Log.i(TAG, "📞 [Servicio] Llamada saliente detectada. Sin tope de ring.")
                return
            }
            // Respaldo: si el teléfono está en OFFHOOK (llamada saliente o activa)
            // no es una llamada entrante sonando. No aplicar tope.
            if (sigueEnLlamadaSalienteOActiva(c)) {
                Log.i(TAG, "📞 [Servicio] Teléfono en OFFHOOK. Sin tope de ring.")
                return
            }
            if (!esDesconocidoFiltrable(c, numero)) return

            numeroEnTope = numero
            runnableTope = Runnable {
                Log.i(TAG, "⏰ [Servicio] $numeroEnTope superó el tope de ring. Colgando llamada.")
                if (sigueSonando(c)) {
                    colgarLlamada(c, numeroEnTope)
                } else {
                    Log.i(TAG, "📴 [Servicio] Llamada $numeroEnTope ya no sonaba al vencer el tope.")
                }
            }
            handler.postDelayed(runnableTope!!, MILIS_TOPE_RING)
            Log.i(TAG, "🔔 [Servicio] Tope de ring (5 tonadas) activado para $numeroEnTope")
        }

        /** Cancela el tope en curso (se contestó, terminó o hay una nueva llamada). */
        fun cancelarTope() {
            runnableTope?.let { handler.removeCallbacks(it) }
            runnableTope = null
            numeroEnTope = ""
        }

        private fun esDesconocidoFiltrable(context: Context, numero: String): Boolean {
            if (numero.isEmpty()) return false
            val prefs = context.getSharedPreferences(
                "FlutterSharedPreferences", Context.MODE_PRIVATE
            )
            val modo = prefs.getString("flutter.modo_operacion", "automatico")
                ?: "automatico"
            if (modo == "desactivado") return false
            if (prefs.getString("flutter.bloquear_desconocidos", "true") != "true") {
                return false
            }
            // Lista blanca en RAM y persistida
            if (MainActivity.listaBlancaTemporal.contains(numero)) return false
            val digitos = FiltroNucleo.soloDigitos(numero)
            val normalizado = FiltroNucleo.normalizar(numero)
            if (prefs.getBoolean("flutter.permitido_$digitos", false) ||
                prefs.getBoolean("flutter.permitido_$normalizado", false)
            ) {
                return false
            }
            // Contactos de la agenda
            if (ContactosHelper.existeContacto(context, numero)) return false
            // Primera llamada de un desconocido: dejar pasar sin tope automático.
            // El usuario decide libremente si contesta o no.
            if (!FiltroNucleo.esNumeroYaLlamado(prefs, numero)) {
                FiltroNucleo.marcarNumeroComoYaLlamado(prefs, numero)
                Log.i(TAG, "🆕 [Servicio] Primera llamada de $numero. Sin tope de ring.")
                return false
            }
            return true
        }

        /** Verifica que la llamada siga sonando antes de colgar. */
        private fun sigueSonando(context: Context): Boolean {
            return try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as TelephonyManager
                tm.callState == TelephonyManager.CALL_STATE_RINGING
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ No se pudo leer el estado de la llamada: ${e.message}")
                true
            }
        }

        /** true si el teléfono está marcando/en llamada (no es una entrante). */
        private fun sigueEnLlamadaSalienteOActiva(context: Context): Boolean {
            return try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as TelephonyManager
                tm.callState == TelephonyManager.CALL_STATE_OFFHOOK
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ No se pudo leer el estado OFFHOOK: ${e.message}")
                false
            }
        }

        private fun colgarLlamada(context: Context, numero: String) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE)
                        as TelecomManager
                    val exito = telecomManager.endCall()
                    if (exito) {
                        Log.i(TAG, "🛑 [Servicio] Llamada colgada por tope de ring: $numero")
                        registrarIntercepcion(context, numero)
                    } else {
                        Log.w(TAG, "⚠️ [Servicio] endCall no colgó la llamada.")
                    }
                } else {
                    Log.w(TAG, "📱 [Servicio] endCall requiere API 28+. Sin corte en este sistema.")
                }
            } catch (e: SecurityException) {
                Log.e(
                    TAG,
                    "❌ [Servicio] Permiso faltante al colgar (requiere MODIFY_PHONE_STATE " +
                        "o ser dialer por defecto). Intentando colgado por accesibilidad."
                )
                // En equipos sin root, se presiona el botón "Rechazar/Colgar" de la
                // pantalla de llamada vía AccessibilityService (si está activado).
                AccesibilidadServicio.solicitarColgado()
            } catch (e: Exception) {
                Log.e(TAG, "❌ [Servicio] Error al colgar: ${e.message}")
            }
        }

        /** Registra la intercepción para el popup de la app y la notificación al desbloquear. */
        private fun registrarIntercepcion(context: Context, numero: String) {
            try {
                val limpio = FiltroNucleo.normalizar(numero)
                if (limpio.isEmpty()) return
                val prefs = context.getSharedPreferences(
                    "FlutterSharedPreferences", Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putString("flutter.ultimo_numero_bloqueado_automatico", limpio)
                    .putString("flutter.ultimo_motivo_bloqueo", "Tope de ring (5 tonadas)")
                    .remove("flutter.popup_mostrado_$limpio")
                    .remove("flutter.notif_unlock_$limpio")
                    .apply()
                Log.i(TAG, "🔔 [Servicio] Intercepción registrada para notificación: $limpio")
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ No se pudo registrar la intercepción: ${e.message}")
            }
        }
    }

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activo = true
        contexto = applicationContext
        instancia = this
        crearCanalNotificacion()
        iniciarNotificacion()
        registrarVigilanciaLlamadas()
        Log.i(TAG, "🚀 [Servicio] Filtro en primer plano activo.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        instancia = null
        activo = false
        cancelarTope()
        limpiarVigilanciaLlamadas()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Vigilancia del estado de llamadas (respaldo Android 9-11 + cancelación)
    // ------------------------------------------------------------------

    private fun registrarVigilanciaLlamadas() {
        try {
            if (phoneStateListener != null) return
            phoneStateListener = object : PhoneStateListener() {
                @Suppress("DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING -> {
                            // Solo se arma el tope si NO hay una llamada saliente en
                            // curso (el RINGING también se dispara cuando el otro
                            // lado de una llamada saliente está sonando).
                            if (InCallServiceImpl.hayLlamadaSaliente()) {
                                Log.i(TAG, "📞 [Servicio] RINGING de llamada saliente. Sin tope.")
                                return
                            }
                            val numero = phoneNumber ?: ""
                            if (numero.isNotEmpty()) {
                                armarTopeRing(this@FiltroServicio, numero)
                            }
                            // Android 12+ entrega el número vacío por privacidad;
                            // el armado lo hace el CallScreeningService.
                        }
                        TelephonyManager.CALL_STATE_OFFHOOK,
                        TelephonyManager.CALL_STATE_IDLE -> cancelarTope()
                    }
                }
            }
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ No se pudo registrar la vigilancia de llamadas: ${e.message}")
        }
    }

    private fun limpiarVigilanciaLlamadas() {
        try {
            val listener = phoneStateListener ?: return
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
            phoneStateListener = null
            telephonyManager = null
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ No se pudo limpiar la vigilancia: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Notificación persistente en Primer Plano
    // ------------------------------------------------------------------

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "Filtro Linea Privada",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activo el filtro de llamadas y SMS desatendido."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(canal)
        }
    }

    private fun iniciarNotificacion() {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        )

        val notificacion: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Linea Privada activa")
            .setContentText("Bloqueo y filtro de llamadas y SMS funcionando en segundo plano.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForegroundCompat(this, NOTIF_ID, notificacion)
        } else {
            startForeground(NOTIF_ID, notificacion)
        }
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat(
        service: Service,
        id: Int,
        notification: Notification
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            service.startForeground(id, notification)
        }
    }
}
