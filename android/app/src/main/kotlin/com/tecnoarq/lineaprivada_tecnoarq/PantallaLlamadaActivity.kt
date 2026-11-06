package com.tecnoarq.lineaprivada_tecnoarq

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.telecom.Call

/**
 * PantallaLlamadaActivity - Pantalla de llamada propia en pantalla completa.
 *
 * Se muestra cuando la app es el dialer por defecto. Muestra el número, el
 * motivo y los botones según el estado:
 *   - Entrante sonando: "Responder" y "Rechazar".
 *   - Desconocida: aviso de auto-corte en ~5 tonos.
 *   - En curso / saliente: botón "Colgar".
 */
class PantallaLlamadaActivity : Activity() {

    companion object {
        @Volatile
        var instancia: PantallaLlamadaActivity? = null
            private set
    }

    private lateinit var txtNumero: TextView
    private lateinit var txtEstado: TextView
    private lateinit var btnResponder: Button
    private lateinit var btnRechazar: Button
    private lateinit var btnColgar: Button
    private lateinit var btnAltavoz: Button
    private lateinit var btnSilencio: Button
    private lateinit var filaEntrante: View
    private lateinit var filaAtajos: View
    private lateinit var panelDtmf: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instancia = this

        // Enciende la pantalla si está apagada. NO se usa showWhenLocked: el
        // keyguard (teclado numérico de PIN) debe quedar al frente al recibir
        // la llamada y, al desbloquear, se ve la pantalla de llamada detrás.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Solicita al sistema mostrar el teclado numérico de desbloqueo al
        // frente de la pantalla de llamada. Al ingresar el PIN, la actividad
        // de llamada queda visible.
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, null)
        }

        setContentView(R.layout.activity_llamada)

        txtNumero = findViewById(R.id.txt_numero)
        txtEstado = findViewById(R.id.txt_estado)
        btnResponder = findViewById(R.id.btn_responder)
        btnRechazar = findViewById(R.id.btn_rechazar)
        btnColgar = findViewById(R.id.btn_colgar)
        btnAltavoz = findViewById(R.id.btn_altavoz)
        btnSilencio = findViewById(R.id.btn_silencio)
        filaEntrante = findViewById(R.id.fila_entrante)
        filaAtajos = findViewById(R.id.fila_atajos)
        panelDtmf = findViewById(R.id.panel_dtmf)

        btnResponder.setOnClickListener { InCallServiceImpl.responderLlamada() }
        btnRechazar.setOnClickListener { InCallServiceImpl.rechazarLlamada() }
        btnColgar.setOnClickListener { InCallServiceImpl.colgarLlamada() }
        btnAltavoz.setOnClickListener {
            InCallServiceImpl.alternarAltavoz()
            actualizar()
        }
        btnSilencio.setOnClickListener {
            InCallServiceImpl.alternarSilencio()
            actualizar()
        }

        // Teclado DTMF para menús de atención al cliente durante llamadas activas.
        val dtmf = listOf(
            R.id.dtmf_1 to '1', R.id.dtmf_2 to '2', R.id.dtmf_3 to '3',
            R.id.dtmf_4 to '4', R.id.dtmf_5 to '5', R.id.dtmf_6 to '6',
            R.id.dtmf_7 to '7', R.id.dtmf_8 to '8', R.id.dtmf_9 to '9',
            R.id.dtmf_asterisco to '*', R.id.dtmf_0 to '0', R.id.dtmf_numeral to '#'
        )
        for ((id, digito) in dtmf) {
            findViewById<Button>(id).setOnClickListener {
                InCallServiceImpl.enviarDtmf(digito)
            }
        }

        actualizar()
    }

    override fun onDestroy() {
        if (instancia == this) instancia = null
        super.onDestroy()
    }

    fun actualizar() {
        if (instancia != this) return

        val call = InCallServiceImpl.llamadaActual
        val numero = call?.details?.handle?.schemeSpecificPart ?: "Llamada"
        txtNumero.text = numero

        val entrante = call?.details?.callDirection == Call.Details.DIRECTION_INCOMING
        // El aviso de auto-corte SOLO aplica a llamadas entrantes desconocidas.
        // Las llamadas salientes (líneas de atención, marcaciones del usuario)
        // nunca se cortan ni muestran ese aviso.
        val esDesconocida = entrante && InCallServiceImpl.esDesconocidaActual

        val estado = when (call?.state) {
            Call.STATE_RINGING -> if (esDesconocida) {
                "Llamada desconocida — se cortará en ~5 tonos"
            } else {
                if (entrante) "Llamada entrante" else "Llamando…"
            }
            Call.STATE_ACTIVE -> "En llamada"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "Llamando…"
            Call.STATE_DISCONNECTED -> "Finalizada"
            else -> InCallServiceImpl.motivoActual
        }
        txtEstado.text = estado

        val mostrandoEntrante = entrante && call?.state == Call.STATE_RINGING
        // En algunos ROMs (MIUI/HyperOS) la llamada conectada puede llegar con
        // estados distintos a STATE_ACTIVE (3), p. ej. 4 o 10. Se considera
        // "en llamada" a cualquier estado que no sea sonando/marcando/cortado.
        val enLlamada = call != null &&
            call.state != Call.STATE_RINGING &&
            call.state != Call.STATE_DIALING &&
            call.state != Call.STATE_CONNECTING &&
            call.state != Call.STATE_DISCONNECTED &&
            call.state != Call.STATE_DISCONNECTING

        // Fila de responder/rechazar solo cuando suena una llamada entrante.
        filaEntrante.visibility = if (mostrandoEntrante) View.VISIBLE else View.GONE

        // Atajos (altavoz, silencio, colgar) visibles durante la llamada activa.
        filaAtajos.visibility = if (enLlamada || !mostrandoEntrante) View.VISIBLE else View.GONE

        // Teclado DTMF siempre visible durante una llamada conectada.
        panelDtmf.visibility = if (enLlamada) View.VISIBLE else View.GONE

        // Refleja el estado de altavoz y silencio en los botones.
        val altavoz = InCallServiceImpl.altavozActivoActual()
        btnAltavoz.text = if (altavoz) "🔊" else "🔉"
        val silencio = InCallServiceImpl.silencioActivoActual()
        btnSilencio.text = if (silencio) "🔇" else "🎤"
    }
}
