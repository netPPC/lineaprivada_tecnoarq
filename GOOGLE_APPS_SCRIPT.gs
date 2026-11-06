/**
 * ============================================================================
 * GOOGLE APPS SCRIPT — Linea Privada TecnoarqueologiC
 * ============================================================================
 * Este script se desplega como Web App para recibir reportes de números de spam
 * desde la app móvil y guardarlos en una hoja de cálculo de Google Sheets.
 *
 * INSTRUCCIONES DE DESPLIEGUE:
 * 1. Abre https://script.google.com/
 * 2. Crea un nuevo proyecto o abre el existente
 * 3. Copia y pega este código en el editor
 * 4. Ve a Desplegar > Nueva implementación
 * 5. Selecciona tipo: "Aplicación web"
 * 6. En "¿Quién tiene acceso al?" SELECCIONA:
 *    >>> "Cualquier persona, incluso usuarios anónimos"  <-- CRÍTICO
 * 7. Haz clic en Desplegar
 * 8. Copia la URL del Web App y úsala en la app como urlFormularioCentral
 * ============================================================================
 */

const ID_HOJA_CALCULO = '1JErCMdU95SFEVV0y3pnYiSPnxnUHC32cPPutVKU_tBc';

/**
 * Maneja peticiones GET y POST del Web App.
 * La app envía los datos tanto por GET (?telefono=...&comentario=...) como
 * por POST (body form-urlencoded).
 */
function doGet(e) {
  return manejarReporte(e);
}

function doPost(e) {
  return manejarReporte(e);
}

function manejarReporte(e) {
  try {
    let telefono = '';
    let comentario = '';

    // Extraer parámetros de GET (query string)
    if (e.parameter) {
      telefono = e.parameter.telefono || '';
      comentario = e.parameter.comentario || '';
    }

    // Extraer parámetros de POST (body)
    if (e.postData && e.postData.contents) {
      const params = new URLSearchParams(e.postData.contents);
      telefono = params.get('telefono') || telefono;
      comentario = params.get('comentario') || comentario;
    }

    // Validación mínima
    if (!telefono) {
      return ContentService.createTextOutput(
        JSON.stringify({ estado: 'error', mensaje: 'Falta parámetro telefono' })
      ).setMimeType(ContentService.MimeType.JSON);
    }

    // Abrir la hoja de cálculo y escribir el reporte
    const hoja = SpreadsheetApp.openById(ID_HOJA_CALCULO).getSheets()[0];
    const fecha = new Date();
    hoja.appendRow([telefono, comentario || 'Denunciado por usuario', fecha]);

    return ContentService.createTextOutput(
      JSON.stringify({ estado: 'ok', mensaje: 'Reporte guardado correctamente' })
    ).setMimeType(ContentService.MimeType.JSON);

  } catch (error) {
    return ContentService.createTextOutput(
      JSON.stringify({ estado: 'error', mensaje: error.toString() })
    ).setMimeType(ContentService.MimeType.JSON);
  }
}

/**
 * (Opcional) Endpoint para descargar la lista negra como CSV
 * Se usa en: https://docs.google.com/spreadsheets/d/ID/export?format=csv&gid=0
 * Esto lo maneja Google Sheets nativamente, no necesita código aquí.
 */
function doGetCsv(e) {
  // No implementado: Google Sheets exporta CSV nativamente
  return ContentService.createTextOutput(
    'use export?format=csv&gid=0 en la URL de la hoja de cálculo'
  );
}
