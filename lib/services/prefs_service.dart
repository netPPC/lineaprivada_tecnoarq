import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

class PrefsService {
  // Canal de comunicación nativo con Kotlin
  static const MethodChannel _platform = MethodChannel(
    'com.example.contestadora/roles',
  );

  // Guardar un texto en Flutter y sincronizarlo con Kotlin de forma automática
  static Future<void> saveString(String key, String value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(key, value);
    try {
      await _platform.invokeMethod('savePrefs', {"key": key, "value": value});
    } catch (e) {
      debugPrint("Error sincronizando key $key con Kotlin: $e");
    }
  }

  // Eliminar un ajuste del disco de Flutter
  static Future<void> remove(String key) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(key);
  }

  // Obtener un texto guardado (si no existe, devuelve el valor por defecto)
  static Future<String> getString(
    String key, {
    String defaultValue = "",
  }) async {
    final prefs = await SharedPreferences.getInstance();
    String valor = prefs.getString(key) ?? "";

    // Fallback: si Flutter no tiene la clave, preguntar a Kotlin.
    // Kotlin escribe directamente en sus propias SharedPreferences con
    // prefijo "flutter.", por eso Flutter no las ve por defecto.
    if (valor.isEmpty) {
      try {
        final nativo = await invokeNativeMethod('getPrefsString', {"key": key});
        if (nativo != null && nativo.toString().isNotEmpty) {
          valor = nativo.toString();
        }
      } catch (e) {
        debugPrint("⚠️ Error leyendo prefs nativo $key: $e");
      }
    }

    return valor.isNotEmpty ? valor : defaultValue;
  }

  // Obtener un booleano guardado (si no existe, devuelve el valor por defecto)
  static Future<bool> getBool(String key, {bool defaultValue = false}) async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(key) ?? defaultValue;
  }

  // Listar todas las claves guardadas (para encontrar pendientes de subida)
  static Future<List<String>> getAllKeys() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getKeys().toList();
  }

  // Guardar un entero
  static Future<void> saveInt(String key, int value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(key, value);
  }

  // Invocar una acción nativa directa en Kotlin pasándole parámetros
  static Future<dynamic> invokeNativeMethod(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    try {
      return await _platform.invokeMethod(method, arguments);
    } catch (e) {
      debugPrint("Error invocando método nativo $method: $e");
      return null;
    }
  }
}
