import 'prefs_service.dart';

/// Servicio de filtrado de SMS (lado Flutter).
///
/// El filtrado real ocurre en Kotlin (SmsReceiver). Esta clase expone la
/// lectura de los SMS filtrados, la solicitud de permisos y las acciones de
/// la interfaz.
class SmsFilterService {
  static const String claveSmsFiltrados = "sms_filtrados";

  /// Lista de SMS filtrados: [{remitente, cuerpo, motivo, fecha}, ...]
  static Future<List<Map<String, dynamic>>> obtenerFiltrados() async {
    final resultado = await PrefsService.invokeNativeMethod(
      'obtenerSmsFiltrados',
    );
    if (resultado is List) {
      return resultado
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
    }
    return [];
  }

  /// Vacía el registro local de SMS filtrados.
  static Future<void> limpiarFiltrados() async {
    await PrefsService.invokeNativeMethod('limpiarSmsFiltrados');
  }

  /// Elimina los SMS filtrados de un remitente concreto (desbloqueo individual).
  static Future<int> removerDeRemitente(String remitente) async {
    final resultado = await PrefsService.invokeNativeMethod(
      'removerSmsDeRemitente',
      {"remitente": remitente},
    );
    return resultado is int ? resultado : 0;
  }

  /// Desbloquea un remitente: lo quita de la lista nativa de bloqueados y de
  /// los SMS filtrados. Reutiliza el mismo desbloqueo que los números.
  static Future<void> desbloquearRemitente(String remitente) async {
    await PrefsService.invokeNativeMethod(
      'desbloquearNumeroNativo',
      {"telefono": remitente},
    );
    await removerDeRemitente(remitente);
  }

  /// Pide al sistema los permisos de SMS y notificaciones.
  static Future<bool> solicitarPermisosSms() async {
    final ok = await PrefsService.invokeNativeMethod('requestSmsPermissions');
    return ok == true;
  }

  /// Abre los ajustes de SMS para configurar la app como gestora de SMS.
  static Future<void> abrirAjustesSms() async {
    await PrefsService.invokeNativeMethod('abrirAjustesSms');
  }
}
