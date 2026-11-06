import 'prefs_service.dart';

/// Puente de acciones nativas entre Flutter y Kotlin.
class CallHandlerService {
  // Quita un número del filtro nativo de bloqueo en segundo plano
  static Future<void> removerNumeroDeFiltroNativo(String numeroSpam) async {
    final Map<String, dynamic> parametros = {"telefono": numeroSpam};
    await PrefsService.invokeNativeMethod(
      'desbloquearNumeroNativo',
      parametros,
    );
  }

  // ------------------------------------------------------------------
  // Lista de bloqueados (para la sección de desbloqueo manual)
  // ------------------------------------------------------------------

  static Future<List<Map<String, dynamic>>> obtenerBloqueados() async {
    final resultado = await PrefsService.invokeNativeMethod('obtenerBloqueados');
    if (resultado is List) {
      return resultado
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
    }
    return [];
  }

  // ------------------------------------------------------------------
  // Ciclo de vida del filtro
  // ------------------------------------------------------------------

  static Future<void> setModoFiltro(String modo) async {
    await PrefsService.saveString("modo_operacion", modo);
    await PrefsService.invokeNativeMethod(
      'setModoFiltro',
      {"modo": modo},
    );
  }

  // ------------------------------------------------------------------
  // Accesibilidad (corte de llamadas en equipos sin root)
  // ------------------------------------------------------------------

  /// Comprueba si el servicio de accesibilidad de corte de llamadas está activo.
  static Future<bool> verificarAccesibilidad() async {
    final resultado =
        await PrefsService.invokeNativeMethod('verificarAccesibilidad');
    return resultado == true;
  }

  /// Abre los Ajustes de Accesibilidad del sistema para activar el servicio
  /// de "Linea Privada — Cortar llamadas".
  static Future<void> abrirAjustesAccesibilidad() async {
    await PrefsService.invokeNativeMethod('abrirAjustesAccesibilidad');
  }

  // ------------------------------------------------------------------
  // Rol de teléfono por defecto (pantalla de llamada propia)
  // ------------------------------------------------------------------

  /// Comprueba si la app es el teléfono (dialer) por defecto.
  static Future<bool> verificarRolDialer() async {
    final resultado = await PrefsService.invokeNativeMethod('verificarRolDialer');
    return resultado == true;
  }

  /// Solicita el rol de teléfono por defecto para mostrar la pantalla de
  /// llamada propia y cortar desconocidos tras 5 tonos.
  static Future<bool> solicitarRolDialer() async {
    final resultado = await PrefsService.invokeNativeMethod('solicitarRolDialer');
    return resultado == true;
  }

  /// Abre el marcador telefónico (DialerActivity) con teclado numérico.
  static Future<bool> abrirMarcador() async {
    final resultado = await PrefsService.invokeNativeMethod('abrirMarcador');
    return resultado == true;
  }
}
