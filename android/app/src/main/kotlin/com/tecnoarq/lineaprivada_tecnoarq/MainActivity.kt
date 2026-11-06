package com.tecnoarq.lineaprivada_tecnoarq

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * MainActivity - Puente principal entre Flutter y el sistema Android.
 *
 * Gestiona permisos y el rol de Call Screening. La lógica de bloqueo y
 * filtrado de llamadas y SMS vive en FiltroNucleo (CallScreeningService,
 * CallReceiver y SmsReceiver).
 */
class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.contestadora/roles"
    private val REQUEST_ID = 5001
    private val REQUEST_ID_DIALER = 5003

    /** Resultados de canales pendientes, separados por código de solicitud,
     *  para evitar dobles respuestas ("Reply already submitted") cuando varias
     *  peticiones de permisos/rol ocurren casi a la vez. */
    private val resultadosPendientes = HashMap<Int, MethodChannel.Result>()

    /** Comprueba si nuestro servicio de accesibilidad está habilitado. */
    private fun esAccesibilidadActiva(): Boolean {
        return try {
            val enabled = android.provider.Settings.Secure.getInt(
                contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            if (!enabled) return false
            val servicios = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val nombre = ComponentName(this, AccesibilidadServicio::class.java)
                .flattenToString()
            servicios.split(':').any { it.equals(nombre, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        val listaBlancaTemporal = HashSet<String>()
        private var instance: MainActivity? = null
        private var pendingEstado: String? = null
        private var pendingNumero: String? = null

        val instanciaActiva: MainActivity?
            get() = instance

        /** Notifica a la UI de Flutter el cambio de estado de una llamada. */
        fun notificarCambioLlamada(estado: String, numero: String) {
            instance?.let { activity ->
                activity.runOnUiThread {
                    try {
                        MethodChannel(
                            activity.flutterEngine!!.dartExecutor.binaryMessenger,
                            "com.example.contestadora/roles"
                        ).invokeMethod(
                            "cambioLlamadaTiempoReal",
                            mapOf("estado" to estado, "numero" to numero)
                        )
                    } catch (e: Exception) {
                        Log.e("TecnoarqMain", "UI no disponible: ${e.message}")
                    }
                }
            } ?: run {
                pendingEstado = estado
                pendingNumero = numero
            }
        }

        /** Notifica a la UI de Flutter que un SMS fue filtrado. */
        fun notificarSmsFiltrado(tipo: String, remitente: String, motivo: String) {
            instance?.let { activity ->
                activity.runOnUiThread {
                    try {
                        MethodChannel(
                            activity.flutterEngine!!.dartExecutor.binaryMessenger,
                            "com.example.contestadora/roles"
                        ).invokeMethod(
                            "cambioSmsFiltrado",
                            mapOf(
                                "tipo" to tipo,
                                "remitente" to remitente,
                                "motivo" to motivo
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("TecnoarqMain", "UI de SMS no disponible: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // CallReceiver solo es válido para Android 9 (API < 29). En Android 10+
        // el filtrado lo hace CallScreeningService; dejar este receptor activo
        // cuelga la llamada (número vacío) antes de evaluar las reglas.
        val callReceiver = ComponentName(this, CallReceiver::class.java)
        val estadoReceptor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        try {
            packageManager.setComponentEnabledSetting(
                callReceiver, estadoReceptor, PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e("TecnoarqMain", "No se pudo ajustar CallReceiver: ${e.message}")
        }
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Despachar eventos pendientes antes de que Flutter encienda
        pendingEstado?.let {
            notificarCambioLlamada(it, pendingNumero ?: "")
            pendingEstado = null
            pendingNumero = null
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler {
                call,
                result ->
            when (call.method) {
                "requestRole" -> {
                    resultadosPendientes[REQUEST_ID] = result
                    solicitarRolFiltroLlamadas()
                }

                "verificarRolDialer" -> {
                    result.success(esDialer())
                }

                "solicitarRolDialer" -> {
                    resultadosPendientes[REQUEST_ID_DIALER] = result
                    solicitarRolDialer()
                }

                "abrirMarcador" -> {
                    try {
                        val intent = Intent(this, DialerActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e("TecnoarqMain", "No se pudo abrir el marcador: ${e.message}")
                        result.success(false)
                    }
                }

                "requestBasePermissions" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        resultadosPendientes[REQUEST_ID + 1] = result
                        val permisos = arrayListOf(
                            android.Manifest.permission.READ_PHONE_STATE,
                            android.Manifest.permission.READ_CONTACTS,
                            android.Manifest.permission.CALL_PHONE,
                            android.Manifest.permission.READ_CALL_LOG
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permisos.add(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        requestPermissions(permisos.toTypedArray(), REQUEST_ID + 1)
                    } else {
                        result.success(true)
                    }
                }

                "requestSmsPermissions" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        resultadosPendientes[REQUEST_ID + 2] = result
                        val permisos = arrayListOf(
                            android.Manifest.permission.RECEIVE_SMS,
                            android.Manifest.permission.READ_SMS
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !permisos.contains(android.Manifest.permission.POST_NOTIFICATIONS)
                        ) {
                            permisos.add(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        requestPermissions(permisos.toTypedArray(), REQUEST_ID + 2)
                    } else {
                        result.success(true)
                    }
                }

                "abrirAjustesSms" -> {
                    try {
                        val intent = Intent("android.settings.ACTION_MMS_SMS_SETTINGS")
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        result.success(false)
                    }
                }

                "savePrefs" -> {
                    val key = call.argument<String>("key") ?: ""
                    val value = call.argument<String>("value") ?: ""
                    val prefs = getSharedPreferences(
                        "FlutterSharedPreferences", Context.MODE_PRIVATE
                    )
                    prefs.edit().putString("flutter.$key", value).apply()
                    result.success(true)
                }

                "getPrefsString" -> {
                    val key = call.argument<String>("key") ?: ""
                    val prefs = getSharedPreferences(
                        "FlutterSharedPreferences", Context.MODE_PRIVATE
                    )
                    // Kotlin guarda con prefijo "flutter."; Flutter lee sin prefijo.
                    // Probamos ambas formas para cubrir lecturas cruzadas.
                    val valor = prefs.getString("flutter.$key", "")
                        ?: prefs.getString(key, "")
                        ?: ""
                    result.success(valor)
                }

                "setModoFiltro" -> {
                    val modo = call.argument<String>("modo") ?: "automatico"
                    val prefs = getSharedPreferences(
                        "FlutterSharedPreferences", Context.MODE_PRIVATE
                    )
                    prefs.edit().putString("flutter.modo_operacion", modo).apply()
                    if (modo == "desactivado") {
                        FiltroServicio.detener(this)
                    } else {
                        FiltroServicio.iniciar(this)
                    }
                    Log.i("TecnoarqMain", "🎛️ Modo filtro cambiado a: $modo")
                    result.success(true)
                }

                "abrirAjustesBateria" -> {
                    try {
                        val intent = Intent(
                            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                        )
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        result.success(false)
                    }
                }

                "verificarAccesibilidad" -> {
                    val activa = esAccesibilidadActiva()
                    Log.i("TecnoarqMain", "🔎 Servicio de accesibilidad activo: $activa")
                    result.success(activa)
                }

                "abrirAjustesAccesibilidad" -> {
                    try {
                        val intent = Intent(
                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                        )
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        result.success(false)
                    }
                }

                "desbloquearNumeroNativo" -> {
                    val telefono = call.argument<String>("telefono") ?: ""
                    if (telefono.isNotEmpty()) {
                        listaBlancaTemporal.add(telefono)
                        val prefs = getSharedPreferences(
                            "FlutterSharedPreferences", Context.MODE_PRIVATE
                        )
                        FiltroNucleo.desbloquear(prefs, telefono)
                        Log.i("TecnoarqMain", "🔓 Número $telefono desbloqueado.")
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }

                "obtenerBloqueados" -> {
                    val prefs = getSharedPreferences(
                        "FlutterSharedPreferences", Context.MODE_PRIVATE
                    )
                    result.success(FiltroNucleo.leerBloqueados(prefs))
                }

                "obtenerSmsFiltrados" -> {
                    val prefs = getSharedPreferences(
                        "FlutterSharedPreferences", Context.MODE_PRIVATE
                    )
                    result.success(FiltroNucleo.leerSmsFiltrados(prefs))
                }

                "obtenerPerdidas" -> {
                    val permiso = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) ==
                        PackageManager.PERMISSION_GRANTED
                    val perdidas = ArrayList<Map<String, String>>()
                    if (permiso) {
                        try {
                            val cursor = contentResolver.query(
                                CallLog.Calls.CONTENT_URI,
                                arrayOf(
                                    CallLog.Calls.NUMBER,
                                    CallLog.Calls.CACHED_NAME,
                                    CallLog.Calls.DATE
                                ),
                                "${CallLog.Calls.TYPE} = ?",
                                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                                "${CallLog.Calls.DATE} DESC"
                            )
                            cursor?.use {
                                while (it.moveToNext()) {
                                    val numero = it.getString(0)?.trim() ?: ""
                                    if (numero.isEmpty()) continue
                                    val nombre = it.getString(1)
                                    val fecha = java.text.DateFormat.getDateTimeInstance()
                                        .format(java.util.Date(it.getLong(2)))
                                    val etiqueta =
                                        if (!nombre.isNullOrEmpty()) "$nombre ($numero)\n$fecha"
                                        else "$numero\n$fecha"
                                    perdidas.add(
                                        mapOf(
                                            "numero" to numero,
                                            "nombre" to (nombre ?: ""),
                                            "etiqueta" to etiqueta
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("TecnoarqMain", "Error leyendo llamadas perdidas: ${e.message}")
                        }
                    }
                    result.success(mapOf("permiso" to permiso, "perdidas" to perdidas))
                }

                "limpiarSmsFiltrados" -> {
                    val prefs = getSharedPreferences(
                        "FlutterSharedPreferences", Context.MODE_PRIVATE
                    )
                    prefs.edit().remove("flutter.sms_filtrados").apply()
                    result.success(true)
                }

                "removerSmsDeRemitente" -> {
                    val remitente = call.argument<String>("remitente") ?: ""
                    val prefs = getSharedPreferences(
                        "FlutterSharedPreferences", Context.MODE_PRIVATE
                    )
                    val eliminados = FiltroNucleo.removerSmsDeRemitente(prefs, remitente)
                    result.success(eliminados)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun solicitarRolFiltroLlamadas() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as RoleManager
            val estaDisponible = roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
            val yaEsFiltro = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

            if (estaDisponible && !yaEsFiltro) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                startActivityForResult(intent, REQUEST_ID)
            } else {
                Log.i("TecnoarqMain", "🛡️ El rol Call Screening ya está asignado.")
                resultadosPendientes.remove(REQUEST_ID)?.success(true)
            }
        } else {
            Log.i("TecnoarqMain", "📱 Sistema antiguo (API < 29). Saltando rol de filtro.")
            resultadosPendientes.remove(REQUEST_ID)?.success(true)
        }
    }

    /** Comprueba si la app es el teléfono (dialer) por defecto. */
    private fun esDialer(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                (getSystemService(Context.ROLE_SERVICE) as RoleManager)
                    .isRoleHeld(RoleManager.ROLE_DIALER)
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    private fun solicitarRolDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as RoleManager
            val estaDisponible = roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)
            val yaEsDialer = roleManager.isRoleHeld(RoleManager.ROLE_DIALER)

            if (estaDisponible && !yaEsDialer) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                startActivityForResult(intent, REQUEST_ID_DIALER)
            } else if (yaEsDialer) {
                Log.i("TecnoarqMain", "📞 La app ya es el teléfono por defecto.")
                resultadosPendientes.remove(REQUEST_ID_DIALER)?.success(true)
            } else {
                Log.e("TecnoarqMain", "❌ El rol de teléfono no está disponible.")
                resultadosPendientes.remove(REQUEST_ID_DIALER)?.success(false)
            }
        } else {
            Log.i("TecnoarqMain", "📱 Sistema antiguo (API < 29). Sin rol de teléfono.")
            resultadosPendientes.remove(REQUEST_ID_DIALER)?.success(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ID) {
            val ok = resultCode == RESULT_OK
            Log.i("TecnoarqMain", if (ok) "✅ Rol de filtro concedido." else "❌ Rol rechazado.")
            resultadosPendientes.remove(REQUEST_ID)?.success(ok)
        } else if (requestCode == REQUEST_ID_DIALER) {
            val ok = resultCode == RESULT_OK
            Log.i(
                "TecnoarqMain",
                if (ok) "📞 Rol de teléfono (dialer) concedido." else "❌ Rol de teléfono rechazado."
            )
            resultadosPendientes.remove(REQUEST_ID_DIALER)?.success(ok)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val todosConcedidos = grantResults.isNotEmpty() && grantResults.all {
            it == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        Log.i("TecnoarqMain", "🛡️ Permisos (req $requestCode) concedidos: $todosConcedidos")
        if (requestCode == REQUEST_ID + 1 || requestCode == REQUEST_ID + 2) {
            // remove() garantiza responder una única vez aunque el sistema
            // entregue el resultado más de una vez.
            resultadosPendientes.remove(requestCode)?.success(todosConcedidos)
        }
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }
}
