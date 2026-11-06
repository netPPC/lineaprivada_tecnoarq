package com.tecnoarq.lineaprivada_tecnoarq

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FiltroNucleo - Motor de reglas compartido por llamadas y SMS.
 *
 * Centraliza TODA la lógica de clasificación para que el CallScreeningService,
 * el CallReceiver (Android 9), el SmsReceiver y la interfaz Flutter usen
 * exactamente las mismas reglas, evitando conflictos y resultados distintos.
 *
 * Reglas implementadas (en orden de evaluación):
 *  1. Modo desactivado                             -> No interviene
 *  2. Número oculto / privado / vacío              -> Bloqueo inmediato
 *  3. Lista blanca / contacto guardado             -> SIEMPRE pasa (antes que
 *                                                     cualquier regla de bloqueo)
 *  4. Internacional (fuera de +56)                 -> Bloqueo inmediato (salvo permiso)
 *  5. Estructura extraña / Call Center / corto     -> Bloqueo inmediato
 *  6. Base de datos comunitaria (Google Drive)     -> Bloqueo inmediato (match completo)
 *  7. Prefijo comunitario quemado (4-5 dígitos)    -> Bloqueo inmediato
 *  8. Ráfaga por número (6+ llamadas en <24h o <4h)-> Bloqueo automático
 *  9. Ráfaga de prefijo (>5 números distintos)     -> Quema el prefijo
 * 10. Desconocido sin reglas                       -> Llamadas: suena con tope de
 *                                                     tonadas (el servicio en
 *                                                     primer plano cuelga; SMS:
 *                                                     se filtra si la opción de
 *                                                     filtrar desconocidos está on).
 *  Todo bloqueo se registra para subida a Drive y para la sección de desbloqueo.
 */
object FiltroNucleo {

    private const val TAG = "TecnoarqFiltro"
    private const val MILIS_24H = 24L * 60L * 60L * 1000L
    private const val MILIS_4H = 4L * 60L * 60L * 1000L

    /** Número de llamadas a partir del cual se bloquea (bloquea en la 6ª, >5). */
    const val UMBRAL_LLAMADAS_BLOQUEO = 5
    const val UMBRAL_PREFIJO_DISTINTOS = 5

    private val formatosHora = listOf(
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US),
        SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.US)
    )

    // ------------------------------------------------------------------
    // Utilidades de normalización
    // ------------------------------------------------------------------

    /** Solo dígitos y signo +. Sin espacios, guiones, puntos ni paréntesis. */
    fun normalizar(numero: String): String {
        return numero
            .replace(" ", "")
            .replace("-", "")
            .replace(".", "")
            .replace("(", "")
            .replace(")", "")
            .trim()
    }

    /** Solo dígitos (sin el +) para comparaciones numéricas. */
    fun soloDigitos(numero: String): String {
        return numero.replace(Regex("[^0-9]"), "")
    }

    /** Devuelve los primeros N dígitos nacionales (usa la versión +56). */
    fun prefijoDe(numero: String, longitud: Int): String {
        val digitos = soloDigitos(normalizar(numero))
        if (digitos.isEmpty()) return ""
        return digitos.substring(0, kotlin.math.min(longitud, digitos.length))
    }

    // ------------------------------------------------------------------
    // Regla 1 y 2: oculto / privado / internacional
    // ------------------------------------------------------------------

    fun esNumeroOculto(numero: String): Boolean {
        val limpio = numero.trim().lowercase()
        val digitos = soloDigitos(numero)
        return limpio.isEmpty() ||
            limpio == "unknown" ||
            limpio == "private" ||
            limpio == "oculto" ||
            limpio == "desconocido" ||
            digitos.isEmpty()
    }

    fun esInternacional(numero: String, permitirInternacionales: Boolean): Boolean {
        if (permitirInternacionales) return false
        val limpio = normalizar(numero)
        // Formato E.164 completo
        if (limpio.startsWith("+")) return !limpio.startsWith("+56")
        // Sin código de país: un móvil/fijo local chileno debe venir con 56 o como 9xxxxxxx
        val digitos = soloDigitos(limpio)
        if (digitos.isEmpty()) return true
        if (digitos.startsWith("56")) return false
        // Número local sin prefijo de país: solo aceptamos móviles de 9 dígitos
        if (digitos.length == 9 && digitos.startsWith("9")) return false
        return true
    }

    // ------------------------------------------------------------------
    // Regla 3: estructura sospechosa / call center
    // ------------------------------------------------------------------

    fun esEstructuraSospechosa(numero: String): Boolean {
        val digitos = soloDigitos(numero)
        if (digitos.isEmpty()) return false
        // Conmutadores y centros de llamada cortos
        if (digitos.length < 8) return true
        // Longitud anómala
        if (digitos.length > 15) return true
        // Sin código de país debe ser móvil local (9xxxxxxxx)
        if (!digitos.startsWith("56")) {
            return !(digitos.length == 9 && digitos.startsWith("9"))
        }
        // Móvil chileno: 569 + 8 dígitos
        if (digitos.startsWith("569")) return digitos.length != 11
        // Fijo Santiago: 562 + 8 dígitos
        if (digitos.startsWith("562")) return digitos.length != 11
        // Otros fijos nacionales con código de área: 10 a 12 dígitos
        return digitos.length < 10 || digitos.length > 12
    }

    // ------------------------------------------------------------------
    // Regla 4 y 5: base de datos comunitaria y prefijos quemados
    // ------------------------------------------------------------------

    /** Busca coincidencia completa en la lista negra CSV. Devuelve el motivo o null. */
    fun buscarEnListaNegra(prefs: SharedPreferences, numero: String): String? {
        val listaNegra = prefs.getString("flutter.lista_negra_csv", "") ?: ""
        if (listaNegra.isBlank()) return null
        val digitoCompleto = normalizar(numero)
        val digitosLimpios = soloDigitos(numero)
        for (fila in listaNegra.split(",")) {
            val partes = fila.split(":")
            if (partes.isEmpty()) continue
            val numeroBase = partes[0].trim()
            val motivo = if (partes.size > 1) partes.subList(1, partes.size).joinToString(":") else "Spam Base de Datos"
            if (numeroBase == digitoCompleto || numeroBase == digitosLimpios) {
                return motivo.ifBlank { "Spam Base de Datos" }
            }
        }
        return null
    }

    /** Busca coincidencia por prefijo (4-5 dígitos) en la lista de prefijos quemados. */
    fun prefijoQuemado(prefs: SharedPreferences, numero: String): String? {
        val prefijos = prefs.getString("flutter.prefijos_bloqueados_sistema", "") ?: ""
        if (prefijos.isBlank()) return null
        val digitos = soloDigitos(numero)
        val lista = prefijos.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        for (prefijo in lista) {
            if (digitos.startsWith(prefijo)) return prefijo
        }
        return null
    }

    // ------------------------------------------------------------------
    // Regla 6 y 7: detección de ráfagas y quemado de prefijos
    // ------------------------------------------------------------------

    /**
     * Registra una llamada desconocida y devuelve el motivo de bloqueo automático
     * si corresponde (6+ llamadas del mismo número en <24h o <4h).
     * Además quema el prefijo si hay >5 números distintos compartiéndolo.
     */
    fun registrarRafagaYEvaluar(prefs: SharedPreferences, numero: String): String? {
        val digitos = soloDigitos(numero)
        if (digitos.length < 8) return null
        val prefijo = prefijoDe(numero, 5)
        if (prefijo.isEmpty()) return null

        val claveHistorial = "tecnoarq_hist_$prefijo"
        val historialRaw = prefs.getString(claveHistorial, "") ?: ""
        val ahora = System.currentTimeMillis()

        // Estructura: "num:count:first:last;num:count:first:last;..."
        val entradas = historialRaw.split(";").filter { it.isNotBlank() }
        val mapa = LinkedHashMap<String, Triple<Int, Long, Long>>()
        for (entrada in entradas) {
            val partes = entrada.split(":")
            if (partes.size == 4) {
                val numeroEntrada = partes[0]
                val count = partes[1].toIntOrNull() ?: 0
                val first = partes[2].toLongOrNull() ?: ahora
                val last = partes[3].toLongOrNull() ?: ahora
                mapa[numeroEntrada] = Triple(count, first, last)
            }
        }

        // Limpieza de entradas viejas (más de 24h)
        val filtrado = LinkedHashMap<String, Triple<Int, Long, Long>>()
        for ((num, datos) in mapa) {
            if (ahora - datos.third <= MILIS_24H) {
                filtrado[num] = datos
            }
        }

        // Actualizar el número actual
        val actual = filtrado[digitos]
        val countNuevo = (actual?.first ?: 0) + 1
        val firstNuevo = actual?.second ?: ahora
        filtrado[digitos] = Triple(countNuevo, firstNuevo, ahora)

        // Serializar de vuelta
        val serializado = filtrado.entries.joinToString(";") { (num, d) ->
            "${num}:${d.first}:${d.second}:${d.third}"
        }
        prefs.edit().putString(claveHistorial, serializado).apply()

        Log.i(TAG, "📊 [Ráfaga] Prefijo $prefijo -> $digitos llamada #$countNuevo (window ${ahora - firstNuevo}ms)")

        // Regla 6: 6+ llamadas del mismo número en menos de 24h, o 6+ en <4h
        if (countNuevo > UMBRAL_LLAMADAS_BLOQUEO && (ahora - firstNuevo) <= MILIS_24H) {
            return "Rafaga: $countNuevo llamadas en 24h"
        }
        if (countNuevo > UMBRAL_LLAMADAS_BLOQUEO && (ahora - firstNuevo) <= MILIS_4H) {
            return "Rafaga: $countNuevo llamadas en 4h"
        }

        // Regla 7: más de 5 números distintos compartiendo el prefijo
        if (filtrado.size > UMBRAL_PREFIJO_DISTINTOS) {
            quemarPrefijo(prefs, prefijo)
            return "Prefijo quemado por volumen comunitario"
        }

        return null
    }

    private fun quemarPrefijo(prefs: SharedPreferences, prefijo: String) {
        val actuales = prefs.getString("flutter.prefijos_bloqueados_sistema", "") ?: ""
        val lista = actuales.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (!lista.contains(prefijo)) {
            val nuevo = if (actuales.isBlank()) prefijo else "$actuales,$prefijo"
            prefs.edit().putString("flutter.prefijos_bloqueados_sistema", nuevo).apply()
            Log.w(TAG, "🚨 [Prefijo] $prefijo quemado por volumen (>$UMBRAL_PREFIJO_DISTINTOS números).")
        }
    }

    // ------------------------------------------------------------------
    // Registro de bloqueos (para Drive, UI y desbloqueo)
    // ------------------------------------------------------------------

    /** Guarda un bloqueo en la lista persistente y lo marca para subir a Drive. */
    fun registrarBloqueo(prefs: SharedPreferences, numero: String, motivo: String) {
        val limpio = normalizar(numero)
        if (limpio.isEmpty()) return

        // 1. Último bloqueo para el popup reactivo
        prefs.edit()
            .putString("flutter.ultimo_numero_bloqueado_automatico", limpio)
            .putString("flutter.ultimo_motivo_bloqueo", motivo)
            .apply()

        // 2. Marca para subida a la hoja de cálculo compartida
        prefs.edit()
            .putBoolean("flutter.pendiente_subir_drive_$limpio", true)
            .putString("flutter.pendiente_motivo_$limpio", motivo)
            .apply()

        // 3. Lista persistente de bloqueados (para desbloqueo manual en la UI)
        val actuales = prefs.getString("flutter.bloqueados_lista", "") ?: ""
        if (!actuales.contains(limpio)) {
            val fecha = formatosHora[0].format(Date())
            val nuevaEntrada = if (actuales.isBlank()) {
                "$limpio|$motivo|$fecha"
            } else {
                "$actuales,$limpio|$motivo|$fecha"
            }
            val partes = nuevaEntrada.split(",").filter { it.isNotBlank() }
            // Máximo 100 entradas para no inflar las preferencias
            val recortada = partes.takeLast(100).joinToString(",")
            prefs.edit().putString("flutter.bloqueados_lista", recortada).apply()
        }

        // 4. Lista negra CSV: para que la próxima llamada de este número caiga
        //    directamente en las reglas de filtrado (buscarEnListaNegra).
        if (!prefs.getString("flutter.lista_negra_csv", "")!!.contains("$limpio:")) {
            val csvActual = prefs.getString("flutter.lista_negra_csv", "") ?: ""
            val nuevaFila = "$limpio:$motivo"
            val csvNuevo = if (csvActual.isBlank()) nuevaFila else "$csvActual,$nuevaFila"
            prefs.edit().putString("flutter.lista_negra_csv", csvNuevo).apply()
        }

        Log.w(TAG, "🛑 [Bloqueo] $limpio registrado -> $motivo")
    }

    /** Desbloquea un número (elimina de lista, prefijos y marca blanca). */
    fun desbloquear(prefs: SharedPreferences, numero: String) {
        val limpio = normalizar(numero)
        val actuales = prefs.getString("flutter.bloqueados_lista", "") ?: ""
        val filtrado = actuales.split(",")
            .filter { it.isNotBlank() && !it.startsWith("$limpio|") }
            .joinToString(",")

        // También elimina de la lista negra CSV para que deje de caer en el filtro
        val csvActual = prefs.getString("flutter.lista_negra_csv", "") ?: ""
        val csvFiltrado = csvActual.split(",")
            .filter { it.isNotBlank() && !it.startsWith("$limpio:") }
            .joinToString(",")

        prefs.edit()
            .putString("flutter.bloqueados_lista", filtrado)
            .putString("flutter.lista_negra_csv", csvFiltrado)
            .putBoolean("flutter.permitido_$limpio", true)
            .remove("flutter.pendiente_subir_drive_$limpio")
            .remove("flutter.pendiente_motivo_$limpio")
            .apply()
        Log.i(TAG, "🔓 [Desbloqueo] $limpio liberado y añadido a lista blanca.")
    }

    /** Extrae la lista de bloqueados persistida como lista de mapas. */
    fun leerBloqueados(prefs: SharedPreferences): List<Map<String, String>> {
        val raw = prefs.getString("flutter.bloqueados_lista", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { entrada ->
                val partes = entrada.split("|")
                if (partes.size >= 1) {
                    buildMap {
                        put("numero", partes[0])
                        put("motivo", if (partes.size > 1) partes[1] else "Bloqueado")
                        put("fecha", if (partes.size > 2) partes[2] else "")
                    }
                } else null
            }
    }

    // ------------------------------------------------------------------
    // Clasificación completa de llamadas
    // ------------------------------------------------------------------

    /** Resultado de clasificar una llamada entrante. */
    data class Clasificacion(
        val motivo: String? = null,
        val esContacto: Boolean = false,
        val esPrimeraLlamada: Boolean = false
    ) {
        val permitida: Boolean get() = motivo == null
    }

    /**
     * Clasificación base compartida por llamadas y SMS (reglas 1-7).
     *  - motivo != null  -> debe bloquearse/filtrarse de inmediato.
     *  - esContacto true -> número legítimo (contacto o lista blanca).
     *  - ambas nulas     -> número desconocido sin regla previa.
     */
    fun clasificarComun(
        context: Context,
        prefs: SharedPreferences,
        numero: String
    ): Clasificacion {
        // 1. Modo desactivado: no intervenir nunca
        if (prefs.getString("flutter.modo_operacion", "automatico") == "desactivado") {
            return Clasificacion()
        }

        // 2. Oculto / privado (sin número no hay nada que consultar)
        if (esNumeroOculto(numero)) return Clasificacion("Número oculto o privado")

        // 3. Lista blanca / contactos: legítimos ANTES que cualquier otra regla.
        //    Sin este orden, un contacto quedaba bloqueado si su número había
        //    sido registrado antes por registrarBloqueo (lista negra CSV),
        //    estaba en un prefijo quemado, o cumplía reglas de estructura o
        //    internacional. También corrige el bug de los SMS: un remitente
        //    desbloqueado (permitido_) se trataba como desconocido y seguía
        //    filtrado. Un contacto guardado SIEMPRE pasa el filtro.
        val digitosNumero = soloDigitos(numero)
        if (prefs.getBoolean("flutter.permitido_$digitosNumero", false) ||
            prefs.getBoolean("flutter.permitido_${normalizar(numero)}", false)
        ) {
            return Clasificacion(esContacto = true)
        }
        if (ContactosHelper.existeContacto(context, numero)) {
            return Clasificacion(esContacto = true)
        }

        // 4. Internacional (fuera de +56)
        val permitirInternacionales =
            prefs.getString("flutter.permitir_internacionales", "false") == "true"
        if (esInternacional(numero, permitirInternacionales)) return Clasificacion("Prefijo internacional")

        // 5. Estructura sospechosa / Call Center
        if (esEstructuraSospechosa(numero)) return Clasificacion("Estructura extraña o Call Center")

        // 6. Base de datos comunitaria (match completo)
        buscarEnListaNegra(prefs, numero)?.let { return Clasificacion(it) }

        // 7. Prefijo quemado
        prefijoQuemado(prefs, numero)?.let { return Clasificacion("Prefijo comunitario bloqueado ($it)") }

        // 8. Desconocido sin reglas previas
        return Clasificacion()
    }

    // ------------------------------------------------------------------
    // Control de primera llamada (números ya conocidos)
    // ------------------------------------------------------------------

    /** Verifica si un número ya ha llamado antes (no es su primera vez). */
    fun esNumeroYaLlamado(prefs: SharedPreferences, numero: String): Boolean {
        val limpio = normalizar(numero)
        if (limpio.isEmpty()) return false
        val lista = prefs.getString("flutter.numeros_ya_llamados", "") ?: ""
        return lista.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .contains(limpio)
    }

    /** Marca un número como ya llamado para distinguir primera vez de posteriores. */
    fun marcarNumeroComoYaLlamado(prefs: SharedPreferences, numero: String) {
        val limpio = normalizar(numero)
        if (limpio.isEmpty()) return
        val actuales = prefs.getString("flutter.numeros_ya_llamados", "") ?: ""
        if (!actuales.contains(limpio)) {
            val nuevo = if (actuales.isBlank()) limpio else "$actuales,$limpio"
            prefs.edit().putString("flutter.numeros_ya_llamados", nuevo).apply()
            Log.i(TAG, "📇 [Primera llamada] $limpio marcado como número ya conocido.")
        }
    }

    /**
     * Clasificación completa para llamadas.
     *  - motivo != null  -> la llamada debe bloquearse de inmediato.
     *  - esContacto      -> llamada legítima, dejar pasar sin intervención.
     *  - esPrimeraLlamada -> desconocido que llama por primera vez: dejar pasar
     *                       sin filtrar, el usuario decide si contesta.
     */
    fun clasificarLlamada(
        context: Context,
        prefs: SharedPreferences,
        numero: String
    ): Clasificacion {
        val base = clasificarComun(context, prefs, numero)

        // Reglas 1-7 resueltas: bloquear lo que bloquea, pasar los contactos
        if (base.motivo != null || base.esContacto) return base

        // 8. Manejo de desconocidos
        val bloquearDesconocidos = prefs.getString("flutter.bloquear_desconocidos", "true") == "true"
        if (bloquearDesconocidos) {
            // Si es la PRIMERA VEZ que llama este número desconocido,
            // registrarlo como conocido y dejar pasar sin filtrar.
            // El usuario decide libremente si contesta o no.
            if (!esNumeroYaLlamado(prefs, numero)) {
                marcarNumeroComoYaLlamado(prefs, numero)
                return Clasificacion(esPrimeraLlamada = true)
            }

            // 8a. Ráfaga del mismo número o prefijo quemado por volumen
            registrarRafagaYEvaluar(prefs, numero)?.let { return Clasificacion(it) }
            // 8b. Desconocido ya conocido sin ráfaga: permite pasar, pero el
            //     servicio en primer plano aplica el tope de ring si nadie contesta.
            return Clasificacion()
        }

        return Clasificacion()
    }

    // ------------------------------------------------------------------
    // Clasificación de SMS
    // ------------------------------------------------------------------

    /** Clasifica un SMS usando las MISMAS reglas que las llamadas (reglas 1-7). */
    fun clasificarSms(
        context: Context,
        prefs: SharedPreferences,
        remitente: String,
        cuerpo: String
    ): String? {
        val base = clasificarComun(context, prefs, remitente)

        // Reglas 1-7 resueltas: filtrar lo que las llamadas bloquean
        if (base.motivo != null) return base.motivo

        // Contacto o lista blanca: SMS legítimo, no se toca
        if (base.esContacto) return null

        // Desconocidos: mismas reglas de volumen que los números (ráfagas)
        val filtrarSms = prefs.getString("flutter.filtrar_sms_desconocidos", "true") == "true"
        if (filtrarSms) {
            registrarRafagaYEvaluar(prefs, remitente)?.let { return "SMS: $it" }
            return "SMS de número desconocido"
        }

        return null
    }

    /** Guarda un SMS filtrado en la lista persistente (máx. 60 entradas). */
    fun guardarSmsFiltrado(
        prefs: SharedPreferences,
        remitente: String,
        cuerpo: String,
        motivo: String
    ) {
        val fecha = formatosHora[0].format(Date())
        val cuerpoSeguro = cuerpo.replace("|", " ").replace(",", " ").take(500)
        val entrada = "$remitente|$cuerpoSeguro|$motivo|$fecha"
        val actuales = prefs.getString("flutter.sms_filtrados", "") ?: ""
        val nueva = if (actuales.isBlank()) entrada else "$entrada,$actuales"
        val partes = nueva.split(",").filter { it.isNotBlank() }
        prefs.edit().putString("flutter.sms_filtrados", partes.take(60).joinToString(",")).apply()
    }

    /** Elimina todos los SMS filtrados de un remitente concreto (desbloqueo). */
    fun removerSmsDeRemitente(prefs: SharedPreferences, remitente: String): Int {
        val actuales = prefs.getString("flutter.sms_filtrados", "") ?: ""
        val filtrado = actuales.split(",")
            .filter { it.isNotBlank() && !it.startsWith("$remitente|") }
            .joinToString(",")
        prefs.edit().putString("flutter.sms_filtrados", filtrado).apply()
        return actuales.split(",").count { it.isNotBlank() } - filtrado.split(",").count { it.isNotBlank() }
    }

    /** Lee los SMS filtrados persistidos. */
    fun leerSmsFiltrados(prefs: SharedPreferences): List<Map<String, String>> {
        val raw = prefs.getString("flutter.sms_filtrados", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { entrada ->
                val partes = entrada.split("|")
                if (partes.size >= 1) {
                    buildMap {
                        put("remitente", partes[0])
                        put("cuerpo", if (partes.size > 1) partes[1] else "")
                        put("motivo", if (partes.size > 2) partes[2] else "Filtrado")
                        put("fecha", if (partes.size > 3) partes[3] else "")
                    }
                } else null
            }
    }
}
