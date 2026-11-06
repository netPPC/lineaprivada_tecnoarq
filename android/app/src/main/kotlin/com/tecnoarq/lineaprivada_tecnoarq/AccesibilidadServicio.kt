package com.tecnoarq.lineaprivada_tecnoarq

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccesibilidadServicio - Respaldo para colgar llamadas en equipos SIN root.
 *
 * Android no permite a apps de terceros colgar llamadas en curso sin
 * MODIFY_PHONE_STATE o sin ser el dialer por defecto. En equipos sin root, la
 * única vía es un AccessibilityService que presione el botón "Rechazar/Colgar"
 * de la pantalla de llamada cuando el tope de ring (5 tonadas) se cumple.
 *
 * No reacciona a eventos: el FiltroServicio lo invoca a través de
 * solicitarColgado() cuando el tope vence y endCall() falló por permisos.
 * Requiere que el usuario active el servicio en Ajustes > Accesibilidad.
 */
class AccesibilidadServicio : AccessibilityService() {

    companion object {
        private const val TAG = "TecnoarqAcces"

        @Volatile
        var instancia: AccesibilidadServicio? = null
            private set

        /** Pide presionar el botón de colgar/rechazar (lo llama el tope de ring). */
        fun solicitarColgado() {
            val svc = instancia
            if (svc == null) {
                Log.w(
                    TAG,
                    "⚠️ [Acces] Servicio de accesibilidad NO activo. " +
                        "Actívalo en Ajustes > Accesibilidad > Linea Privada."
                )
                return
            }
            svc.presionarColgar()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instancia = this
        Log.i(TAG, "✅ [Acces] Servicio de accesibilidad conectado.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instancia = null
        Log.i(TAG, "🔌 [Acces] Servicio de accesibilidad desconectado.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instancia = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No se necesita reaccionar a eventos; el tope invoca solicitarColgado().
    }

    override fun onInterrupt() {
        // No implementado
    }

    /** Busca y presiona el botón de colgar/rechazar en la pantalla de llamada. */
    fun presionarColgar() {
        try {
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "⚠️ [Acces] No hay ventana activa para buscar el botón.")
                return
            }
            val nodo = buscarBotonColgar(root)
            if (nodo == null) {
                Log.w(TAG, "⚠️ [Acces] No se encontró el botón de colgar/rechazar.")
                return
            }
            nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "👆 [Acces] Botón de colgar/rechazar presionado.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [Acces] Error al presionar colgar: ${e.message}")
        }
    }

    private fun buscarBotonColgar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pila = ArrayDeque<AccessibilityNodeInfo>()
        pila.addLast(root)
        while (pila.isNotEmpty()) {
            val nodo = pila.removeLast()
            if (esBotonColgar(nodo)) return nodo
            for (i in 0 until nodo.childCount) {
                nodo.getChild(i)?.let { pila.addLast(it) }
            }
        }
        return null
    }

    private fun esBotonColgar(nodo: AccessibilityNodeInfo): Boolean {
        if (!nodo.isClickable) return false
        val vid = nodo.viewIdResourceName ?: ""
        val texto = (nodo.text ?: "").toString().lowercase()
        val desc = (nodo.contentDescription ?: "").toString().lowercase()

        // IDs típicos del botón de colgar/rechazar en los dialers AOSP, MIUI, EMUI
        val ids = listOf(
            "end", "reject", "decline", "hangup", "hang_up", "hang_up_button",
            "rechazar", "refuse"
        )
        if (ids.any { vid.contains(it, ignoreCase = true) }) return true

        // Textos y descripciones en ES, EN y ZH
        val palabras = listOf(
            "colgar", "rechazar", "finalizar", "rechazo", "terminar llamada",
            "end", "end call", "decline", "reject", "hang up", "refuse",
            "拒绝", "挂断", "拒接"
        )
        if (palabras.any { texto.contains(it) || desc.contains(it) }) return true

        return false
    }
}
