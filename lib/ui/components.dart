import 'package:flutter/material.dart';

/// Color corporativo oficial de TecnoarqueologiC Soluciones TI.
const Color kColorTecnoarq = Color.fromARGB(255, 58, 171, 183);

/// Genera los títulos de sección con el color corporativo oficial.
Widget buildSeccionTitulo(String titulo, {IconData? icono}) {
  return Row(
    children: [
      if (icono != null) ...[
        Icon(icono, size: 18, color: kColorTecnoarq),
        const SizedBox(width: 6),
      ],
      Text(
        titulo,
        style: const TextStyle(
          fontSize: 15,
          fontWeight: FontWeight.bold,
          color: kColorTecnoarq,
        ),
      ),
    ],
  );
}

/// Tarjeta genérica con encabezado.
Widget buildTarjeta({
  required Widget child,
  Color? color,
  EdgeInsets padding = const EdgeInsets.all(12),
}) {
  return Card(
    elevation: 4,
    color: color,
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    child: Padding(padding: padding, child: child),
  );
}
