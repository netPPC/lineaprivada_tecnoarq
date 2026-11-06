package com.tecnoarq.lineaprivada_tecnoarq

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

object ContactosHelper {

    private const val TAG = "TecnoarqContactos"

    /**
     * Revisa de manera ultra rápida si un número telefónico entrante
     * existe guardado en la lista oficial de contactos del celular.
     */
    fun existeContacto(context: Context, numeroEntrante: String): Boolean {
        if (numeroEntrante.isEmpty()) return false

        try {
            // Usamos la API oficial de Android para consultar la base de datos de contactos
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(numeroEntrante)
            )

            // Consultamos solo la columna del nombre para ahorrar memoria y velocidad
            val proyeccion = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor = context.contentResolver.query(uri, proyeccion, null, null, null)

            cursor?.use {
                if (it.moveToFirst()) {
                    val nombreContacto = it.getString(0)
                    Log.i(TAG, "🟢 Número reconocido. Es el contacto agendado: $nombreContacto")
                    return true // El número existe en los contactos
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error al consultar la agenda de contactos nativa: ${e.message}")
        }

        Log.w(TAG, "ℹ️ Número no encontrado en la agenda de contactos del dispositivo.")
        return false // Es un número desconocido
    }
}
