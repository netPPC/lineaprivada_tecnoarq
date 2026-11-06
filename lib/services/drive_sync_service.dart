import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'prefs_service.dart';

/// Sincronización con la hoja de cálculo pública de Google Drive.
///
///  - Descarga la base de datos comunitaria (CSV) y la guarda para el filtro.
///  - Sube los números bloqueados automáticamente (pendientes) y las denuncias
///    manuales al formulario/script de la hoja.
class DriveSyncService {
  /// Límite de espera para cada petición de red, para que la sincronización
  /// nunca se quede colgada sin respuesta.
  static const Duration timeoutRed = Duration(seconds: 25);

  /// Verifica conectividad básica antes de intentar sincronizar con Drive.
  /// Usa el endpoint de detección de portal cautivo de Google (204 No Content).
  static Future<bool> tieneConectividad() async {
    try {
      final response = await http
          .get(Uri.parse('https://clients3.google.com/generate_204'))
          .timeout(const Duration(seconds: 8));
      return response.statusCode == 204;
    } catch (_) {
      return false;
    }
  }
  // Fragmentación segura de la URL de descarga del CSV
  static const String idGoogleSheets =
      '1JErCMdU95SFEVV0y3pnYiSPnxnUHC32cPPutVKU_tBc';
  static const String protocolo = 'https://';
  static const String dominioDocs = 'docs.google.com';
  static const String rutaExport = '/spreadsheets/d/';
  static final String baseServidor = protocolo + dominioDocs + rutaExport;
  static const String parametrosDescarga = '/export?format=csv&gid=0';
  static final String urlDirectaSheets =
      baseServidor + idGoogleSheets + parametrosDescarga;

  // Fragmentación segura de la URL de la Macro para reportes POST
  static const String idMacroGoogle =
      'AKfycbyQueD3D_nE0gQPs3oNFEpyVOOJCQgd00w5J70envR9ibRYFUKnBC_FnjYLifHlijuLlw';
  static const String dominioScript = 'script.google.com';
  static const String rutaMacros = '/macros/s/';
  static const String endpointExec = '/exec';
  static final String urlFormularioCentral =
      protocolo + dominioScript + rutaMacros + idMacroGoogle + endpointExec;

  // ------------------------------------------------------------------
  // Sincronización de la lista negra (descarga)
  // ------------------------------------------------------------------

  /// Descarga la lista negra del CSV de Google Drive y la guarda en memoria
  /// compartida con Kotlin. Devuelve la cantidad de registros válidos o null.
  static Future<int?> sincronizarListaNegraDrive() async {
    try {
      final response = await http
          .get(Uri.parse(urlDirectaSheets))
          .timeout(timeoutRed);
      if (response.statusCode != 200) return null;

      List<String> registrosConComentario = [];
      List<String> lineas = response.body.split(RegExp(r'\r?\n'));

      for (String linea in lineas) {
        String lineaLimpia = linea.trim();
        if (lineaLimpia.isEmpty || lineaLimpia.startsWith('#')) {
          continue;
        }

        List<String> columnas = lineaLimpia.split(',');
        if (columnas.isNotEmpty) {
          String numero = columnas[0]
              .replaceAll('"', '')
              .replaceAll("'", '')
              .replaceAll('-', '')
              .replaceAll('.', '')
              .replaceAll('(', '')
              .replaceAll(')', '')
              .replaceAll(RegExp(r'[^\+0-9]'), '')
              .trim();

          // Solo filas de datos: la primera columna debe ser un número.
          // El encabezado (u otras líneas con texto en el comentario) ya no
          // descarta números legítimos como "56985884083,numero robado".
          if (RegExp(r'^\+?[0-9]+$').hasMatch(numero) && numero.length >= 6) {
            String comentario = "Spam No Identificado";
            if (columnas.length > 1) {
              String comentarioLimpio = columnas[1]
                  .replaceAll('"', '')
                  .replaceAll("'", '')
                  .trim();
              if (comentarioLimpio.isNotEmpty) {
                comentario = comentarioLimpio;
              }
            }
            registrosConComentario.add("$numero:$comentario");
          }
        }
      }

      if (registrosConComentario.isEmpty) return 0;

      // Conservar los bloqueos locales aún pendientes de subir (sospechosos,
      // bloqueos manuales/automáticos) para que no desaparezcan al sobrescribir
      // con la base comunitaria de Drive.
      final listaActual = await PrefsService.getString("lista_negra_csv",
          defaultValue: "");
      final localPendiente = <String>{};
      final keysActuales = await PrefsService.getAllKeys();
      for (final k in keysActuales) {
        if (k.startsWith("pendiente_subir_drive_")) {
          final numero = k.replaceFirst("pendiente_subir_drive_", "");
          if (numero.isNotEmpty) localPendiente.add(numero);
        }
      }
      for (final fila in listaActual.split(',')) {
        if (fila.trim().isEmpty) continue;
        final numeroBase = fila.split(':').first.trim();
        if (numeroBase.isNotEmpty && localPendiente.contains(numeroBase)) {
          registrosConComentario.add(fila);
        }
      }

      String listaParaKotlin = registrosConComentario.join(',');
      await PrefsService.saveString("lista_negra_csv", listaParaKotlin);
      await PrefsService.saveInt("ultima_sincronizacion_ok",
          DateTime.now().millisecondsSinceEpoch);

      return registrosConComentario.length;
    } catch (e) {
      debugPrint("❌ Error de red en DriveSyncService: $e");
      return null;
    }
  }

  // ------------------------------------------------------------------
  // Subida de reportes (denuncias y bloqueos automáticos)
  // ------------------------------------------------------------------

  /// Sube una denuncia manual (número + nombre/motivo) a la hoja pública.
  ///
  /// Intenta hasta 2 veces si falla por red. Registra el código de estado
  /// y el cuerpo de la respuesta para facilitar el diagnóstico.
  static Future<bool> enviarReporteAlServidor(
    String numero,
    String motivo,
  ) async {
    // Verificar conectividad básica antes de intentar
    final conectado = await tieneConectividad();
    if (!conectado) {
      debugPrint("⚠️ Sin conexión a internet al subir reporte manual de $numero");
      return false;
    }

    int intento = 0;
    while (intento < 2) {
      intento++;
      try {
        // Estrategia 1: GET con query parameters (formato más común para Google Apps Script
        // Web Apps desplegadas con acceso anónimo "Cualquier persona, incluso usuarios anónimos").
        final uriGet = Uri.parse(urlFormularioCentral).replace(
          queryParameters: {
            "telefono": numero,
            "comentario": motivo,
          },
        );
        final responseGet = await http
            .get(uriGet)
            .timeout(timeoutRed);

        final getOk = _respuestaEsValida(responseGet);

        debugPrint(
            "📤 [Drive] Reporte $numero GET intento $intento -> HTTP ${responseGet.statusCode} valido=${getOk}");

        if (getOk) {
          return true;
        }

        // Estrategia 2: POST con body form-urlencoded (fallback)
        final responsePost = await http
            .post(
              Uri.parse(urlFormularioCentral),
              body: {"telefono": numero, "comentario": motivo},
              headers: {
                "Content-Type": "application/x-www-form-urlencoded",
                "Accept": "text/plain, */*",
              },
            )
            .timeout(timeoutRed);

        final postOk = _respuestaEsValida(responsePost);

        debugPrint(
            "📤 [Drive] Reporte $numero POST intento $intento -> HTTP ${responsePost.statusCode} valido=${postOk}");

        if (postOk) {
          return true;
        }

        // Si ambos fallan con 4xx permanente (403, 401, etc.), no reintentar
        if ((responseGet.statusCode >= 400 && responseGet.statusCode < 500) ||
            (responsePost.statusCode >= 400 && responsePost.statusCode < 500)) {
          debugPrint(
              "❌ [Drive] Error del servidor (GET ${responseGet.statusCode} / POST ${responsePost.statusCode}) al subir $numero");
          return false;
        }
      } catch (e) {
        debugPrint("⚠️ [Drive] Intento $intento falló para $numero: $e");
        if (intento >= 2) {
          debugPrint("❌ [Drive] No se pudo subir el reporte de $numero tras 2 intentos");
          return false;
        }
        // Pequeña pausa antes del siguiente intento
        await Future.delayed(const Duration(seconds: 2));
      }
    }
    return false;
  }

  /// Valida que la respuesta HTTP sea realmente del script y no una página
  /// de login de Google u otro error. Una respuesta válida tiene código 2xx
  /// y el cuerpo no contiene indicadores de página de autenticación.
  static bool _respuestaEsValida(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      return false;
    }
    final cuerpo = response.body;
    // Rechazar páginas de login de Google o errores HTML
    if (cuerpo.contains('accounts.google.com/v3/signin') ||
        cuerpo.contains('window[\'ppConfig\']') ||
        cuerpo.contains('<html') ||
        cuerpo.trim().isEmpty) {
      return false;
    }
    // Una respuesta válida del script debería ser JSON o texto plano corto
    return true;
  }

  /// Sube todos los bloqueos automáticos pendientes marcados por Kotlin y
  /// limpia las marcas de los que lograron subirse. Devuelve cuántos subió.
  static Future<int> subirPendientesDrive() async {
    int subidos = 0;
    try {
      final keys = await PrefsService.getAllKeys();
      final pendientes = keys
          .where((k) => k.startsWith("pendiente_subir_drive_"))
          .toList();

      for (final key in pendientes) {
        final numero = key.replaceFirst("pendiente_subir_drive_", "");
        if (numero.isEmpty) continue;

        final motivo = await PrefsService.getString("pendiente_motivo_$numero",
            defaultValue: "Bloqueo automático");

        final ok = await enviarReporteAlServidor(numero, motivo);
        if (ok) {
          await PrefsService.remove(key);
          await PrefsService.remove("pendiente_motivo_$numero");
          subidos++;
        }
      }
      return subidos;
    } catch (e) {
      debugPrint("❌ Error subiendo pendientes a Drive: $e");
      return subidos;
    }
  }
}
