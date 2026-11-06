import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';
import 'dart:io';

// Arquitectura modular de Linea Privada TecnoarqueologiC
import 'services/prefs_service.dart';
import 'services/drive_sync_service.dart';
import 'services/call_handler_service.dart';
import 'services/sms_filter_service.dart';
import 'ui/components.dart';
import 'ui/panels.dart';
import 'ui/splash_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const LineaPrivadaApp());
}

class LineaPrivadaApp extends StatelessWidget {
  const LineaPrivadaApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Linea Privada TecnoarqueologiC',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: kColorTecnoarq,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: SplashScreen(builder: (context) => const HomeScreen()),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const platform = MethodChannel('com.example.contestadora/roles');

  // Estado maestro
  bool _appEncendida = true;
  String _modoOperacion = "automatico";

  // Reglas de filtrado
  bool _bloquearDesconocidos = true;
  bool _bloquearSpam = true;
  bool _permitirInternacionales = false;
  bool _filtrarSmsDesconocidos = true;

  // Estados de trabajo
  bool _sincronizando = false;

  List<Map<String, dynamic>> _bloqueados = [];
  List<Map<String, dynamic>> _smsFiltrados = [];

  @override
  void initState() {
    super.initState();
    _inicializarSistemaSecuencial();
    _configurarEscuchadorEventos();
  }

  // ------------------------------------------------------------------
  // Inicialización
  // ------------------------------------------------------------------

  Future<void> _inicializarSistemaSecuencial() async {
    try {
      String encendidaStr = await PrefsService.getString(
        "app_encendida",
        defaultValue: "true",
      );
      _appEncendida = encendidaStr != "false";

      _modoOperacion = await PrefsService.getString(
        "modo_operacion",
        defaultValue: "automatico",
      );
      // Migración: el modo supervisado se eliminó. Ahora todo es automático.
      if (_modoOperacion == "supervisado") {
        _modoOperacion = "automatico";
        await PrefsService.saveString("modo_operacion", "automatico");
      }
      if (!_appEncendida) _modoOperacion = "desactivado";

      _bloquearDesconocidos =
          await PrefsService.getString("bloquear_desconocidos",
              defaultValue: "true") == "true";
      _bloquearSpam =
          await PrefsService.getString("bloquear_spam", defaultValue: "true") ==
              "true";
      _permitirInternacionales =
          await PrefsService.getString("permitir_internacionales",
              defaultValue: "false") == "true";
      _filtrarSmsDesconocidos =
          await PrefsService.getString("filtrar_sms_desconocidos",
              defaultValue: "true") == "true";

      setState(() {});

      await _solicitarPermisosTelefonosSistema();
      await _refrescarBloqueados();
      await _refrescarSmsFiltrados();

      // Sincronizar el modo con el motor nativo
      await CallHandlerService.setModoFiltro(_modoOperacion);

      // Subir bloqueos pendientes acumulados
      unawaited(_subirPendientesAlServidor());

      await _verificarYMostrarBloqueoReciente();
    } catch (e) {
      debugPrint("❌ Error en secuencia de arranque: $e");
    }
  }

  Future<void> _solicitarPermisosTelefonosSistema() async {
    try {
      await platform.invokeMethod('requestBasePermissions');
    } catch (e) {
      debugPrint("⚠️ Permisos base no desplegados: $e");
    }
    try {
      await platform.invokeMethod('requestSmsPermissions');
    } catch (e) {
      debugPrint("⚠️ Permisos SMS no desplegados: $e");
    }

    if (!Platform.isAndroid) return;

    try {
      final bool? resultadoRol = await platform.invokeMethod<bool>(
        'requestRole',
      );
      if (resultadoRol == true) {
        await PrefsService.saveString("app_defecto_configurada_ok", "true");
        debugPrint("✅ App configurada como filtro por defecto.");
      }
    } catch (e) {
      debugPrint("⚠️ Error al solicitar el rol nativo: $e");
    }

    // Inmunidad de batería para HyperOS/EMUI/Android
    String bateriaStr = await PrefsService.getString(
      "bateria_inmune_ok",
      defaultValue: "false",
    );
    if (bateriaStr != "true" && mounted) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (BuildContext context) => AlertDialog(
          title: const Text("🛡️ Inmunidad de Fondo"),
          content: const Text(
            "Para que el filtro bloquee y filtre llamadas y SMS de forma desatendida "
            "en EMUI/HyperOS/Android, desactiva el ahorro de energía para esta app.\n\n"
            "Al continuar, selecciona 'Sin Restricciones' o desactiva la optimización.",
          ),
          actions: [
            ElevatedButton(
              onPressed: () async {
                Navigator.pop(context);
                await PrefsService.saveString("bateria_inmune_ok", "true");
                await PrefsService.invokeNativeMethod('abrirAjustesBateria');
              },
              child: const Text("CONFIGURAR AHORA"),
            ),
          ],
        ),
      );
    }

    // Accesibilidad para cortar llamadas (necesario en equipos sin root)
    if (mounted) {
      final accesibilidadOk = await PrefsService.getString(
        "accesibilidad_ok",
        defaultValue: "false",
      );
      bool accesActiva = false;
      try {
        accesActiva = await CallHandlerService.verificarAccesibilidad();
      } catch (e) {
        debugPrint("⚠️ No se pudo verificar accesibilidad: $e");
      }
      if (accesibilidadOk != "true" && !accesActiva && mounted) {
        showDialog(
          context: context,
          barrierDismissible: false,
          builder: (BuildContext context) => AlertDialog(
            title: const Text("✋ Corte de llamadas (Accesibilidad)"),
            content: const Text(
              "Android no permite que apps de terceros cuelguen llamadas sin root. "
              "Para que Linea Privada corte las llamadas de desconocidos tras 5 tonos, "
              "activa el servicio de accesibilidad.\n\n"
              "Al continuar, abre Ajustes > Accesibilidad y activa "
              "'Linea Privada — Cortar llamadas'.",
            ),
            actions: [
              TextButton(
                onPressed: () {
                  Navigator.pop(context);
                  PrefsService.saveString("accesibilidad_ok", "true");
                },
                child: const Text("NO AHORA"),
              ),
              ElevatedButton(
                onPressed: () async {
                  Navigator.pop(context);
                  await PrefsService.saveString("accesibilidad_ok", "true");
                  await CallHandlerService.abrirAjustesAccesibilidad();
                },
                child: const Text("ACTIVAR"),
              ),
            ],
          ),
        );
      }
    }

    // Pantalla de llamada propia (rol de teléfono por defecto)
    if (mounted) {
      final dialerOk = await PrefsService.getString(
        "dialer_configurado_ok",
        defaultValue: "false",
      );
      bool yaEsDialer = false;
      try {
        yaEsDialer = await CallHandlerService.verificarRolDialer();
      } catch (e) {
        debugPrint("⚠️ No se pudo verificar el rol de teléfono: $e");
      }
      if (dialerOk != "true" && !yaEsDialer && mounted) {
        showDialog(
          context: context,
          barrierDismissible: false,
          builder: (BuildContext context) => AlertDialog(
            title: const Text("📞 Pantalla de Llamada Propia"),
            content: const Text(
              "Para que Linea Privada muestre SU PROPIA pantalla de llamada "
              "(número, motivo y botones) y corte las llamadas de desconocidos "
              "tras 5 tonos de forma nativa, la app debe ser el TELÉFONO por defecto.\n\n"
              "⚠️ Al activarlo, Linea Privada gestionará las llamadas entrantes "
              "y salientes. Puedes revertirlo en Ajustes > Apps > Aplicación de "
              "teléfono.",
            ),
            actions: [
              TextButton(
                onPressed: () {
                  Navigator.pop(context);
                  PrefsService.saveString("dialer_configurado_ok", "true");
                },
                child: const Text("NO AHORA"),
              ),
              ElevatedButton(
                onPressed: () async {
                  Navigator.pop(context);
                  await PrefsService.saveString("dialer_configurado_ok", "true");
                  await CallHandlerService.solicitarRolDialer();
                },
                child: const Text("ACTIVAR COMO TELÉFONO"),
              ),
            ],
          ),
        );
      }
    }
  }

  Future<void> _refrescarBloqueados() async {
    final lista = await CallHandlerService.obtenerBloqueados();
    if (mounted) setState(() => _bloqueados = lista);
  }

  Future<void> _refrescarSmsFiltrados() async {
    final lista = await SmsFilterService.obtenerFiltrados();
    if (mounted) setState(() => _smsFiltrados = lista);
  }

  // ------------------------------------------------------------------
  // Escuchador de eventos nativos
  // ------------------------------------------------------------------

  void _configurarEscuchadorEventos() {
    platform.setMethodCallHandler((MethodCall call) async {
      if (call.method == "cambioLlamadaTiempoReal") {
        final Map<dynamic, dynamic> datos =
            call.arguments as Map<dynamic, dynamic>;
        final String estado = datos["estado"] ?? "REPOSO";

        if (estado == "BLOQUEADO") {
          await _refrescarBloqueados();
        }

        if (estado == "REPOSO") {
          if (mounted && Navigator.canPop(context)) {
            Navigator.pop(context);
          }
        }
      }

      if (call.method == "cambioSmsFiltrado") {
        await _refrescarSmsFiltrados();
        final Map<dynamic, dynamic> datos =
            call.arguments as Map<dynamic, dynamic>;
        final String remitente = datos["remitente"] ?? "";
        if (mounted && remitente.isNotEmpty) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('📩 SMS filtrado de $remitente'),
              duration: const Duration(seconds: 2),
              backgroundColor: Colors.redAccent,
            ),
          );
        }
      }
    });
  }

  // ------------------------------------------------------------------
  // Cambios de modo
  // ------------------------------------------------------------------

  Future<void> _toggleApp(bool valor) async {
    setState(() => _appEncendida = valor);
    await PrefsService.saveString("app_encendida", valor ? "true" : "false");
    if (!valor) {
      _modoOperacion = "desactivado";
      await PrefsService.saveString("modo_operacion", "desactivado");
      await CallHandlerService.setModoFiltro("desactivado");
      setState(() {});
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text("🔌 Linea Privada apagada. Modo normal activo."),
            backgroundColor: Colors.orange,
          ),
        );
      }
    } else {
      _modoOperacion = "automatico";
      await PrefsService.saveString("modo_operacion", "automatico");
      await CallHandlerService.setModoFiltro("automatico");
      setState(() {});
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text("🛡️ Linea Privada encendida en modo Automático."),
            backgroundColor: Colors.teal,
          ),
        );
      }
    }
  }

  /// Abre el marcador telefónico nativo con teclado numérico.
  Future<void> _abrirMarcador() async {
    try {
      final ok = await CallHandlerService.abrirMarcador();
      if (!ok && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text("No se pudo abrir el marcador."),
            backgroundColor: Colors.redAccent,
          ),
        );
      }
    } catch (e) {
      debugPrint("❌ Error al abrir el marcador: $e");
    }
  }

  // ------------------------------------------------------------------
  // Sincronización y denuncias
  // ------------------------------------------------------------------

  Future<void> _sincronizarListaGlobal() async {
    // Verificar conectividad antes de intentar sincronizar
    final conectado = await DriveSyncService.tieneConectividad();
    if (!conectado && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            '📶 Sin conexión a internet. No se puede sincronizar con Google Drive.',
          ),
          backgroundColor: Colors.amber,
          duration: Duration(seconds: 4),
        ),
      );
      return;
    }

    setState(() => _sincronizando = true);
    try {
      int? registros = await DriveSyncService.sincronizarListaNegraDrive();
      int subidos = await _subirPendientesAlServidor();
      if (!mounted) return;
      if (registros != null && registros > 0) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '☁️ Base sincronizada: $registros números · $subidos bloqueos subidos.',
            ),
            backgroundColor: const Color.fromARGB(255, 58, 183, 131),
          ),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              '⚠️ No se pudieron descargar registros válidos del servidor.',
            ),
            backgroundColor: Colors.amber,
          ),
        );
      }
    } finally {
      setState(() => _sincronizando = false);
    }
  }

  Future<int> _subirPendientesAlServidor() async {
    try {
      return await DriveSyncService.subirPendientesDrive();
    } catch (e) {
      debugPrint("❌ Error subiendo pendientes: $e");
      return 0;
    }
  }

  void _mostrarDialogoReporte(BuildContext context) {
    final numeroCtrl = TextEditingController();
    final motivoCtrl = TextEditingController();

    showDialog(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Denuncia Manual Drive'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: numeroCtrl,
                decoration: const InputDecoration(
                  labelText: 'Número (Ej: +56981884083)',
                ),
                keyboardType: TextInputType.phone,
              ),
              TextField(
                controller: motivoCtrl,
                decoration: const InputDecoration(
                  labelText: 'Nombre / Identificado como (opcional)',
                ),
              ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.history, size: 18),
                  label: const Text('Elegir de Llamadas perdidas'),
                  onPressed: () async {
                    final resultado = await platform.invokeMapMethod<String, dynamic>(
                      'obtenerPerdidas',
                    );
                    if (!dialogContext.mounted) return;
                    final permiso = (resultado?['permiso'] as bool?) ?? false;
                    final perdidas =
                        (resultado?['perdidas'] as List?) ?? const <dynamic>[];
                    if (!permiso) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text(
                            'Falta el permiso "Registro de llamadas" para leer las perdidas. '
                            'Actívalo en Ajustes > Apps > Linea Privada > Permisos.',
                          ),
                          backgroundColor: Colors.amber,
                        ),
                      );
                      return;
                    }
                    if (perdidas.isEmpty) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text('No hay llamadas perdidas registradas.'),
                          backgroundColor: Colors.amber,
                        ),
                      );
                      return;
                    }
                    if (!dialogContext.mounted) return;
                    showDialog(
                      context: dialogContext,
                      builder: (pickerContext) => SimpleDialog(
                        title: const Text('Elegir llamada perdida'),
                        children: perdidas.map((elemento) {
                          final map = elemento as Map<dynamic, dynamic>;
                          final numero = (map['numero'] as String?) ?? '';
                          final etiqueta = (map['etiqueta'] as String?) ?? numero;
                          return SimpleDialogOption(
                            onPressed: () {
                              numeroCtrl.text = numero;
                              Navigator.pop(pickerContext);
                            },
                            child: Text(etiqueta, style: const TextStyle(fontSize: 14)),
                          );
                        }).toList(),
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            onPressed: () async {
              final numReportado = numeroCtrl.text.trim();
              final motivoReportado = motivoCtrl.text.trim();
              if (numReportado.isEmpty) return;
              Navigator.pop(dialogContext);

              bool exito = await DriveSyncService.enviarReporteAlServidor(
                numReportado,
                motivoReportado.isEmpty
                    ? 'Denunciado por usuario'
                    : motivoReportado,
              );
              if (!context.mounted) return;

              if (!exito) {
                // Guardar como pendiente para reintento automático posterior
                await PrefsService.saveString(
                    "pendiente_subir_drive_$numReportado", "true");
                await PrefsService.saveString(
                    "pendiente_motivo_$numReportado",
                    motivoReportado.isEmpty
                        ? 'Denunciado por usuario'
                        : motivoReportado);
                debugPrint(
                    "💾 [Drive] Reporte $numReportado guardado como pendiente local.");
              }

              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text(
                    exito
                        ? '🚀 Número $numReportado subido a la base pública.'
                        : '⚠️ No se pudo subir ahora. Guardado como pendiente para reintento automático.',
                  ),
                  backgroundColor: exito ? Colors.green : Colors.orange,
                  duration: const Duration(seconds: 4),
                ),
              );
            },
            child: const Text('Subir Servidor'),
          ),
        ],
      ),
    ).then((_) {
      numeroCtrl.dispose();
      motivoCtrl.dispose();
    });
  }

  // ------------------------------------------------------------------
  // Popup reactivo de bloqueos recientes
  // ------------------------------------------------------------------

  Future<void> _verificarYMostrarBloqueoReciente() async {
    if (!Platform.isAndroid || !mounted) return;

    final ultimo = await PrefsService.getString(
      "ultimo_numero_bloqueado_automatico",
    );
    if (ultimo.isEmpty) return;

    final yaVisto = await PrefsService.getString(
      "popup_mostrado_$ultimo",
      defaultValue: "",
    );
    if (yaVisto == "1") return;

    final motivo = await PrefsService.getString(
      "ultimo_motivo_bloqueo",
      defaultValue: "Call Center / Spam",
    );

    if (!mounted) return;
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return AlertDialog(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          title: const Row(
            children: [
              Icon(Icons.gpp_bad, color: Colors.redAccent, size: 30),
              SizedBox(width: 10),
              Expanded(
                child: Text(
                  "Intercepción Reciente",
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 18),
                ),
              ),
            ],
          ),
          content: Text(
            "La Linea Privada detectó actividad de un número desconocido:\n\n"
            "📞 $ultimo\n\n"
            "Motivo: $motivo\n\n"
            "¿Qué deseas hacer?",
            style: const TextStyle(fontSize: 14, color: Colors.white70),
          ),
          actions: [
            TextButton(
              onPressed: () async {
                await CallHandlerService.removerNumeroDeFiltroNativo(ultimo);
                await PrefsService.remove("ultimo_numero_bloqueado_automatico");
                await PrefsService.remove("ultimo_motivo_bloqueo");
                await PrefsService.saveString("popup_mostrado_$ultimo", "1");
                await _refrescarBloqueados();
                if (!context.mounted) return;
                Navigator.pop(context);
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text("🔓 Número $ultimo desbloqueado."),
                    backgroundColor: Colors.teal,
                  ),
                );
              },
              child: const Text(
                "DESBLOQUEAR NÚMERO",
                style: TextStyle(
                  color: Colors.greenAccent,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            ElevatedButton(
              onPressed: () async {
                await PrefsService.saveString("popup_mostrado_$ultimo", "1");
                await PrefsService.remove("ultimo_numero_bloqueado_automatico");
                await PrefsService.remove("ultimo_motivo_bloqueo");
                if (!context.mounted) return;
                Navigator.pop(context);
              },
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.red.shade900,
              ),
              child: const Text("DEJAR BLOQUEADO"),
            ),
          ],
        );
      },
    );
  }

  // ------------------------------------------------------------------
  // Interfaz
  // ------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.phone_in_talk, color: kColorTecnoarq),
            SizedBox(width: 8),
            Text(
              'Linea Privada',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                letterSpacing: 1,
              ),
            ),
          ],
        ),
        centerTitle: true,
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(2),
          child: Container(
            height: 2,
            color: _appEncendida ? Colors.tealAccent : Colors.orange,
          ),
        ),
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          await _refrescarBloqueados();
          await _refrescarSmsFiltrados();
        },
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Modo de operación + switch
              buildPanelModos(
                appEncendida: _appEncendida,
                onToggleApp: _toggleApp,
              ),

              const SizedBox(height: 16),

              // Acceso directo al marcador telefónico
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  style: FilledButton.styleFrom(
                    backgroundColor: const Color.fromARGB(255, 46, 125, 50),
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(16),
                    ),
                  ),
                  icon: const Icon(Icons.dialpad, size: 22),
                  label: const Text(
                    'Marcar número',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  onPressed: _abrirMarcador,
                ),
              ),

              const SizedBox(height: 24),
              buildSeccionTitulo(
                'Configuración de Bloqueo',
                icono: Icons.security,
              ),
              const SizedBox(height: 10),
              buildTarjeta(
                child: Column(
                  children: [
                    SwitchListTile(
                      title: const Text('Bloquear Números Desconocidos'),
                      subtitle: const Text(
                        'Bloquea automáticamente los números fuera de tu agenda',
                      ),
                      value: _bloquearDesconocidos,
                      secondary: const Icon(
                        Icons.phone_paused,
                        color: Colors.amber,
                      ),
                      onChanged: (bool value) {
                        setState(() => _bloquearDesconocidos = value);
                        PrefsService.saveString(
                          "bloquear_desconocidos",
                          value ? "true" : "false",
                        );
                      },
                    ),
                    const Divider(height: 1),
                    SwitchListTile(
                      title: const Text('Filtro Anti-Spam Activo'),
                      subtitle: const Text(
                        'Detecta y bloquea llamadas sospechosas',
                      ),
                      value: _bloquearSpam,
                      secondary: const Icon(
                        Icons.verified_user,
                        color: Colors.redAccent,
                      ),
                      onChanged: (bool value) {
                        setState(() => _bloquearSpam = value);
                        PrefsService.saveString(
                          "bloquear_spam",
                          value ? "true" : "false",
                        );
                      },
                    ),
                    const Divider(height: 1),
                    SwitchListTile(
                      title: const Text('Permitir Prefijos Internacionales'),
                      subtitle: const Text(
                        'No bloquear llamadas fuera de +56',
                      ),
                      value: _permitirInternacionales,
                      secondary: const Icon(
                        Icons.public,
                        color: Colors.blueAccent,
                      ),
                      onChanged: (bool value) {
                        setState(() => _permitirInternacionales = value);
                        PrefsService.saveString(
                          "permitir_internacionales",
                          value ? "true" : "false",
                        );
                      },
                    ),
                    const Divider(height: 1),
                    SwitchListTile(
                      title: const Text('Filtrar SMS de Desconocidos'),
                      subtitle: const Text(
                        'Cuarentena mensajes de números fuera de la agenda',
                      ),
                      value: _filtrarSmsDesconocidos,
                      secondary: const Icon(
                        Icons.sms_failed,
                        color: Colors.orangeAccent,
                      ),
                      onChanged: (bool value) {
                        setState(() => _filtrarSmsDesconocidos = value);
                        PrefsService.saveString(
                          "filtrar_sms_desconocidos",
                          value ? "true" : "false",
                        );
                      },
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 24),
              buildSeccionTitulo(
                'Sincronización con Google Drive',
                icono: Icons.cloud_sync,
              ),
              const SizedBox(height: 10),
              buildTarjeta(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text(
                      "La base de datos comunitaria se descarga y los bloqueos automáticos se suben a la hoja pública.",
                      style: TextStyle(fontSize: 12, color: Colors.grey),
                    ),
                    const SizedBox(height: 12),
                    ElevatedButton.icon(
                      onPressed: _sincronizando ? null : _sincronizarListaGlobal,
                      icon: _sincronizando
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.cloud_download),
                      label: Text(
                        _sincronizando
                            ? "SINCRONIZANDO..."
                            : "Sincronizar Lista Global (Manual)",
                      ),
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 24),
              buildSeccionTitulo(
                'Reportar Número a Base de Datos Pública',
                icono: Icons.cloud_upload,
              ),
              const SizedBox(height: 10),
              buildTarjeta(
                color: Colors.blueGrey.shade900,
                child: ListTile(
                  leading: const Icon(
                    Icons.cloud_upload,
                    color: Colors.blueAccent,
                    size: 32,
                  ),
                  title: const Text('¿Te llamó un estafador?'),
                  subtitle: const Text(
                    'Sube el número y su nombre a la lista comunitaria de Google Drive.',
                  ),
                  trailing: ElevatedButton(
                    onPressed: () => _mostrarDialogoReporte(context),
                    child: const Text('Reportar'),
                  ),
                ),
              ),

              const SizedBox(height: 24),
              buildSeccionTitulo(
                'Números Bloqueados',
                icono: Icons.block,
              ),
              const SizedBox(height: 10),
              _buildPanelBloqueados(),

              const SizedBox(height: 24),
              buildSeccionTitulo(
                'SMS Filtrados',
                icono: Icons.sms_failed,
              ),
              const SizedBox(height: 10),
              _buildPanelSms(),

              const SizedBox(height: 40),
              const Center(
                child: Column(
                  children: [
                    Text(
                      "Beta 2.0 · Bloqueador + Filtro + Anti-Spam + SMS",
                      style: TextStyle(color: Colors.grey, fontSize: 11),
                    ),
                    SizedBox(height: 4),
                    Text(
                      "Desarrollado por TecnoarqueologiC Soluciones TI.",
                      style: TextStyle(
                        color: kColorTecnoarq,
                        fontWeight: FontWeight.bold,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 20),
            ],
          ),
        ),
      ),
    );
  }

  // ------------------------------------------------------------------
  // Secciones de la interfaz
  // ------------------------------------------------------------------

  Widget _buildPanelBloqueados() {
    if (_bloqueados.isEmpty) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(20),
          child: Center(
            child: Text(
              'No hay números bloqueados.',
              style: TextStyle(color: Colors.grey, fontStyle: FontStyle.italic),
            ),
          ),
        ),
      );
    }
    return buildTarjeta(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: () async {
                final confirmar = await _confirmarDialogo(
                  context,
                  "Desbloquear todos los números",
                  "¿Estás seguro de que quieres desbloquear todos los números de la lista?",
                );
                if (!confirmar) return;
                for (final b in List.of(_bloqueados)) {
                  await CallHandlerService.removerNumeroDeFiltroNativo(
                    b['numero'].toString(),
                  );
                }
                await _refrescarBloqueados();
                if (mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text("🔓 Todos los números desbloqueados."),
                      backgroundColor: Colors.teal,
                    ),
                  );
                }
              },
              icon: const Icon(Icons.lock_open, size: 16),
              label: const Text(
                "Desbloquear todos",
                style: TextStyle(fontSize: 11),
              ),
            ),
          ),
          const SizedBox(height: 4),
          ..._bloqueados.map((b) {
            final numero = b['numero'].toString();
            final motivo = b['motivo'].toString();
            final fecha = b['fecha'].toString();
            return ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.block, color: Colors.redAccent),
              title: Text(
                numero,
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                ),
              ),
              subtitle: Text(
                '$motivo · $fecha',
                style: const TextStyle(fontSize: 10, color: Colors.grey),
              ),
              trailing: IconButton(
                tooltip: "Desbloquear",
                icon: const Icon(
                  Icons.lock_open,
                  color: Colors.greenAccent,
                ),
                onPressed: () async {
                  await CallHandlerService.removerNumeroDeFiltroNativo(numero);
                  await _refrescarBloqueados();
                },
              ),
            );
          }),
        ],
      ),
    );
  }

  Widget _buildPanelSms() {
    return buildTarjeta(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  "SMS interceptados por las reglas de filtrado.",
                  style: TextStyle(fontSize: 11, color: Colors.grey),
                ),
              ),
              TextButton.icon(
                onPressed: () async {
                  await SmsFilterService.solicitarPermisosSms();
                },
                icon: const Icon(Icons.perm_device_information, size: 14),
                label: const Text(
                  "Permisos SMS",
                  style: TextStyle(fontSize: 10),
                ),
              ),
            ],
          ),
          if (_smsFiltrados.isEmpty)
            const Padding(
              padding: EdgeInsets.all(16),
              child: Center(
                child: Text(
                  'No hay SMS filtrados todavía.',
                  style: TextStyle(
                    color: Colors.grey,
                    fontStyle: FontStyle.italic,
                  ),
                ),
              ),
            )
          else ...[
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton.icon(
                  onPressed: () async {
                    final confirmar = await _confirmarDialogo(
                      context,
                      "Vaciar registro de SMS",
                      "¿Estás seguro de que quieres eliminar todos los SMS filtrados?",
                    );
                    if (!confirmar) return;
                    await SmsFilterService.limpiarFiltrados();
                    await _refrescarSmsFiltrados();
                  },
                  icon: const Icon(Icons.delete_sweep, size: 16),
                  label: const Text(
                    "Vaciar registro",
                    style: TextStyle(fontSize: 11),
                  ),
                ),
                TextButton.icon(
                  onPressed: _desbloquearTodosSms,
                  icon: const Icon(Icons.lock_open, size: 16),
                  label: const Text(
                    "Desbloquear todos",
                    style: TextStyle(fontSize: 11),
                  ),
                ),
              ],
            ),
            ..._smsFiltrados.map((sms) {
              final remitente = sms['remitente'].toString();
              return ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                leading: const Icon(
                  Icons.sms_failed,
                  color: Colors.orangeAccent,
                ),
                title: Text(
                  remitente,
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                subtitle: Text(
                  '${sms['motivo']} · ${sms['fecha']}\n${sms['cuerpo']}',
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 10, color: Colors.grey),
                ),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      tooltip: "Leer SMS",
                      icon: const Icon(
                        Icons.visibility,
                        color: kColorTecnoarq,
                        size: 20,
                      ),
                      onPressed: () => _leerSmsSeguro(sms),
                    ),
                    IconButton(
                      tooltip: "Desbloquear remitente",
                      icon: const Icon(
                        Icons.lock_open,
                        color: Colors.greenAccent,
                        size: 20,
                      ),
                      onPressed: () async {
                        await SmsFilterService.desbloquearRemitente(remitente);
                        await _refrescarSmsFiltrados();
                        await _refrescarBloqueados();
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                              content: Text("🔓 $remitente desbloqueado."),
                              backgroundColor: Colors.teal,
                            ),
                          );
                        }
                      },
                    ),
                  ],
                ),
                onTap: () => _leerSmsSeguro(sms),
              );
            }),
          ],
        ],
      ),
    );
  }

  /// Lectura segura de un SMS: el cuerpo se muestra como TEXTO PLANO inerte.
  /// No se activan links, imágenes, HTML ni markdown: nada se renderiza.
  /// Solo se muestran los caracteres tal cual vienen del remitente.
  void _leerSmsSeguro(Map<String, dynamic> sms) {
    final remitente = sms['remitente'].toString();
    final cuerpo = sms['cuerpo'].toString();
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Row(
          children: [
            const Icon(Icons.sms, color: kColorTecnoarq, size: 22),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                remitente,
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
              ),
            ),
          ],
        ),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '${sms['motivo']} · ${sms['fecha']}',
                style: const TextStyle(fontSize: 11, color: Colors.grey),
              ),
              const SizedBox(height: 12),
              // Cuerpo como texto plano puro: nada se activa al tocarlo.
              Text(
                cuerpo.isEmpty ? "(sin contenido)" : cuerpo,
                style: const TextStyle(fontSize: 14, height: 1.4),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  const Icon(Icons.shield, color: Colors.teal, size: 14),
                  const SizedBox(width: 4),
                  const Expanded(
                    child: Text(
                      "Modo lectura segura: este texto no contiene enlaces activos.",
                      style: TextStyle(fontSize: 10, color: Colors.teal),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () async {
              await SmsFilterService.desbloquearRemitente(remitente);
              await _refrescarSmsFiltrados();
              await _refrescarBloqueados();
              if (!context.mounted) return;
              Navigator.pop(context);
            },
            child: const Text(
              "DESBLOQUEAR",
              style: TextStyle(color: Colors.greenAccent),
            ),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(context),
            child: const Text("Cerrar"),
          ),
        ],
      ),
    );
  }

  Future<void> _desbloquearTodosSms() async {
    final remitentes = _smsFiltrados
        .map((sms) => sms['remitente'].toString())
        .toSet();
    for (final remitente in remitentes) {
      if (remitente.isEmpty) continue;
      await CallHandlerService.removerNumeroDeFiltroNativo(remitente);
    }
    await SmsFilterService.limpiarFiltrados();
    await _refrescarSmsFiltrados();
    await _refrescarBloqueados();
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text("🔓 ${remitentes.length} remitente(s) desbloqueado(s)."),
          backgroundColor: Colors.teal,
        ),
      );
    }
  }

  /// Diálogo de confirmación para acciones destructivas.
  /// Devuelve true si el usuario confirma.
  Future<bool> _confirmarDialogo(
    BuildContext context,
    String titulo,
    String mensaje,
  ) async {
    final resultado = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(
          titulo,
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        content: Text(mensaje),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('CANCELAR'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.red.shade900,
            ),
            child: const Text(
              'CONFIRMAR',
              style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
            ),
          ),
        ],
      ),
    );
    return resultado == true;
  }
}
