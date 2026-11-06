import 'dart:async';
import 'package:flutter/material.dart';
import 'components.dart';

/// Página de inicio original: logo, nombre de la app y crédito del desarrollador.
class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key, required this.builder});

  /// Construye la pantalla siguiente al terminar la animación.
  final WidgetBuilder builder;

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> {
  Timer? _temporizador;

  @override
  void initState() {
    super.initState();
    _temporizador = Timer(const Duration(milliseconds: 2800), () {
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        PageRouteBuilder(
          pageBuilder: (_, _, _) => widget.builder(context),
          transitionsBuilder: (_, animation, _, child) {
            return FadeTransition(opacity: animation, child: child);
          },
          transitionDuration: const Duration(milliseconds: 500),
        ),
      );
    });
  }

  @override
  void dispose() {
    _temporizador?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color.fromARGB(255, 14, 30, 36),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // Imagen de presentación de la app
            ClipRRect(
              borderRadius: BorderRadius.circular(32),
              child: Image.asset(
                'assets/splash.png',
                width: 240,
                height: 320,
                fit: BoxFit.cover,
              ),
            ),
            const SizedBox(height: 28),
            const Text(
              "LÍNEA PRIVADA",
              style: TextStyle(
                fontSize: 26,
                fontWeight: FontWeight.w900,
                letterSpacing: 3,
                color: Colors.white,
              ),
            ),
            const SizedBox(height: 4),
            const Text(
              "Bloqueador · Filtro · Anti-Spam · SMS",
              style: TextStyle(
                fontSize: 13,
                letterSpacing: 1,
                color: Colors.tealAccent,
              ),
            ),
            const SizedBox(height: 40),
            const CircularProgressIndicator(
              color: kColorTecnoarq,
              strokeWidth: 2.5,
            ),
          ],
        ),
      ),
      bottomNavigationBar: const SafeArea(
        child: Padding(
          padding: EdgeInsets.all(18),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                "Desarrollado por TecnoarqueologiC Soluciones TI.",
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                  color: kColorTecnoarq,
                ),
              ),
              SizedBox(height: 4),
              Text(
                "Beta · Android 9 → 16 · HyperOS · EMUI",
                style: TextStyle(fontSize: 10, color: Colors.grey),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
