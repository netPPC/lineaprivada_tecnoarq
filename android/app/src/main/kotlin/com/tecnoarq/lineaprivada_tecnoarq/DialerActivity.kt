package com.tecnoarq.lineaprivada_tecnoarq

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.TelecomManager
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

/**
 * DialerActivity - Marcadora con teclado numérico estilo nativo.
 *
 * Android exige que la app candidata al rol de teléfono maneje el intent
 * ACTION_DIAL. Este dialer permite marcar un número con el teclado 0-9, *, #
 * y realizar la llamada. Es elegible como teléfono por defecto (misma app).
 *
 * Incluye accesos directos a contactos, llamadas perdidas y altavoz, y
 * durante una llamada activa el teclado envía tonos DTMF (menús IVR).
 */
class DialerActivity : Activity() {

    companion object {
        @Volatile
        var instancia: DialerActivity? = null
            private set

        /** true si el marcador está en primer plano (visible). */
        fun instanciaVisible(): Boolean = instancia != null

        /** Refresca la UI del marcador (número, botón en llamada, altavoz). */
        fun notificarActualizacion() {
            val activity = instancia ?: return
            activity.runOnUiThread {
                activity.actualizarEstado()
            }
        }
    }

    private lateinit var editNumero: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instancia = this
        setContentView(R.layout.activity_dialer)

        editNumero = findViewById(R.id.edit_numero)

        val botones = listOf(
            R.id.btn_1 to "1", R.id.btn_2 to "2", R.id.btn_3 to "3",
            R.id.btn_4 to "4", R.id.btn_5 to "5", R.id.btn_6 to "6",
            R.id.btn_7 to "7", R.id.btn_8 to "8", R.id.btn_9 to "9",
            R.id.btn_asterisco to "*", R.id.btn_0 to "0", R.id.btn_numeral to "#"
        )
        for ((id, digito) in botones) {
            findViewById<Button>(id).setOnClickListener { agregarDigito(digito) }
        }

        findViewById<Button>(R.id.btn_borrar).setOnClickListener {
            borrarUltimoDigito()
        }

        // Botón borrar con toque largo: limpia todo.
        findViewById<Button>(R.id.btn_borrar).setOnLongClickListener {
            editNumero.setText("")
            true
        }

        // El teclado usa iconos tecla_* con el dígito y sus letras integradas,
        // así que aquí no se añade texto adicional.

        findViewById<Button>(R.id.btn_llamar).setOnClickListener {
            if (InCallServiceImpl.llamadaActual != null) {
                // Con el marcador visible durante la llamada, el botón grande
                // "📞 En llamada" no debe re-marcar (crearía una segunda llamada).
                Toast.makeText(this, "Ya hay una llamada en curso", Toast.LENGTH_SHORT).show()
            } else {
                llamar(editNumero.text.toString())
            }
        }

        findViewById<Button>(R.id.btn_colgar).setOnClickListener {
            colgar()
        }

        findViewById<Button>(R.id.btn_contactos).setOnClickListener {
            abrirContactos()
        }

        // Agregar el número marcado directamente a la agenda de contactos
        findViewById<Button>(R.id.btn_agregar_contacto).setOnClickListener {
            agregarAContactos(editNumero.text.toString())
        }

        findViewById<Button>(R.id.btn_perdidas).setOnClickListener {
            abrirPerdidas()
        }

        findViewById<Button>(R.id.btn_altavoz).setOnClickListener {
            alternarAltavoz()
        }

        // Pre-cargar el número y, si viene por ACTION_CALL, iniciar la llamada.
        // Al marcar desde Contactos el sistema envía ACTION_CALL (tel:...): la
        // interlocución telefónica debe colocarse con TelecomManager.placeCall
        // (re-lanzar el intent ACTION_CALL volvería a esta misma actividad y
        // provocaría un bucle).
        val uri: Uri? = intent?.data
        val accion = intent?.action
        if ((accion == Intent.ACTION_DIAL || accion == Intent.ACTION_CALL) && uri != null) {
            val numero = uri.schemeSpecificPart ?: ""
            if (numero.isNotEmpty()) {
                editNumero.setText(numero)
                editNumero.setSelection(numero.length)
                if (accion == Intent.ACTION_CALL) {
                    llamar(numero)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Si la actividad ya está abierta (singleTop) y llega una nueva marcación
        // desde Contactos, pegar el número y llamar de inmediato.
        val uri: Uri? = intent.data
        val accion = intent.action
        if ((accion == Intent.ACTION_DIAL || accion == Intent.ACTION_CALL) && uri != null) {
            val numero = uri.schemeSpecificPart ?: ""
            if (numero.isNotEmpty()) {
                editNumero.setText(numero)
                editNumero.setSelection(numero.length)
                if (accion == Intent.ACTION_CALL) {
                    llamar(numero)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        actualizarEstado()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instancia === this) {
            instancia = null
        }
    }

    private fun enLlamadaActiva(): Boolean {
        val call = InCallServiceImpl.llamadaActual ?: return false
        // En algunos ROMs la llamada conectada llega con estados distintos a
        // STATE_ACTIVE (3), p. ej. 4 o 10. Se considera "en llamada" cualquier
        // estado que no sea sonando/marcando/cortado.
        return call.state != Call.STATE_RINGING &&
            call.state != Call.STATE_DIALING &&
            call.state != Call.STATE_CONNECTING &&
            call.state != Call.STATE_DISCONNECTED &&
            call.state != Call.STATE_DISCONNECTING
    }

    private fun actualizarEstado() {
        val enLlamada = enLlamadaActiva()
        // Si hay una llamada activa, mostrar el número de esa llamada.
        if (enLlamada) {
            val numero = InCallServiceImpl.llamadaActual
                ?.details?.handle?.schemeSpecificPart ?: ""
            if (numero.isNotEmpty()) {
                editNumero.setText(numero)
            }
            // El botón de llamar pasa a comportarse como "colgar" visual.
            findViewById<Button>(R.id.btn_llamar).text = "📞 En llamada"
        } else {
            findViewById<Button>(R.id.btn_llamar).text = "📞"
        }
        // Refleja el estado del altavoz.
        val altavoz = InCallServiceImpl.altavozActivoActual()
        findViewById<Button>(R.id.btn_altavoz).text = if (altavoz) "🔊" else "🔈"
    }

    private fun agregarDigito(digito: String) {
        // Durante una llamada conectada el teclado envía tonos DTMF (menús IVR).
        if (enLlamadaActiva()) {
            InCallServiceImpl.enviarDtmf(digito[0])
            return
        }
        val actual = editNumero.text.toString()
        if (actual.length < 20) {
            editNumero.setText(actual + digito)
            editNumero.setSelection(editNumero.text.length)
        }
    }

    private fun borrarUltimoDigito() {
        val actual = editNumero.text.toString()
        if (actual.isNotEmpty()) {
            editNumero.setText(actual.dropLast(1))
            editNumero.setSelection(editNumero.text.length)
        }
    }

    private fun llamar(numero: String) {
        val limpio = numero.trim().replace(" ", "").replace("-", "")
        if (limpio.isEmpty()) {
            Toast.makeText(this, "Ingresa un número", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // Como el dialer es el teléfono por defecto, la llamada se coloca
            // vía Telecom en lugar de lanzar ACTION_CALL (causaría un bucle
            // porque este dialer es el handler de esa acción).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                tm.placeCall(Uri.fromParts("tel", limpio, null), Bundle())
            } else {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$limpio")))
            }
            Log.i("TecnoarqDialer", "📞 Llamada a $limpio colocada por Telecom.")
        } catch (e: SecurityException) {
            Log.e("TecnoarqDialer", "❌ placeCall sin permiso/rol: ${e.message}")
            fallbackLlamar(limpio)
        } catch (e: Exception) {
            Log.e("TecnoarqDialer", "❌ Error al iniciar la llamada: ${e.message}")
            fallbackLlamar(limpio)
        }
    }

    /** Respaldo: lanza ACTION_CALL únicamente si no somos el teléfono por defecto. */
    private fun fallbackLlamar(limpio: String) {
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$limpio")))
        } catch (e: Exception) {
            Toast.makeText(
                this, "No se pudo iniciar la llamada: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun colgar() {
        if (InCallServiceImpl.llamadaActual != null) {
            InCallServiceImpl.colgarLlamada()
        } else {
            Toast.makeText(this, "No hay llamada en curso", Toast.LENGTH_SHORT).show()
        }
    }

    private fun abrirContactos() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this, "No se pudo abrir Contactos: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Abre el editor de contactos con el número marcado pre-cargado. */
    private fun agregarAContactos(numero: String) {
        val limpio = numero.trim().replace(" ", "").replace("-", "")
        if (limpio.isEmpty()) {
            Toast.makeText(this, "Marca un número primero", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, limpio)
            intent.putExtra(
                ContactsContract.Intents.Insert.PHONE_TYPE,
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            )
            startActivity(intent)
            Toast.makeText(this, "Preparando contacto para $limpio", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, "No se pudo abrir el editor: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun abrirPerdidas() {
        try {
            // Consulta el registro de llamadas y muestra las perdidas en un
            // diálogo propio (el dialer del sistema está deshabilitado, así que
            // no se puede abrir su recents con un intent).
            val numeros = ArrayList<String>()
            val etiquetas = ArrayList<String>()
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val numero = it.getString(0) ?: ""
                    val nombre = it.getString(1)
                    val fecha = java.text.DateFormat.getDateTimeInstance()
                        .format(java.util.Date(it.getLong(2)))
                    val etiqueta = when {
                        !nombre.isNullOrEmpty() -> "$nombre ($numero)\n$fecha"
                        numero.isNotEmpty() -> "$numero\n$fecha"
                        else -> "Desconocido\n$fecha"
                    }
                    numeros.add(numero)
                    etiquetas.add(etiqueta)
                }
            }

            if (etiquetas.isEmpty()) {
                Toast.makeText(this, "No hay llamadas perdidas", Toast.LENGTH_SHORT).show()
                return
            }

            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, etiquetas)
            AlertDialog.Builder(this)
                .setTitle("Llamadas perdidas")
                .setAdapter(adapter) { _, pos ->
                    val numero = numeros[pos]
                    if (numero.isNotEmpty()) {
                        llamar(numero)
                    }
                }
                .setNegativeButton("Cerrar", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(
                this, "No se pudo leer llamadas: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun alternarAltavoz() {
        if (InCallServiceImpl.llamadaActual != null) {
            InCallServiceImpl.alternarAltavoz()
            actualizarEstado()
            return
        }
        // Fuera de una llamada: alterna el modo manos libres del sistema.
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            val nuevo = !am.isSpeakerphoneOn
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = nuevo
            findViewById<Button>(R.id.btn_altavoz).text = if (nuevo) "🔊" else "🔈"
        } catch (e: Exception) {
            Toast.makeText(this, "Altavoz: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
