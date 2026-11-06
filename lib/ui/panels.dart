import 'package:flutter/material.dart';
import 'components.dart';

/// Panel principal: switch de encendido/apagado del bloqueador automático.
Widget buildPanelModos({
  required bool appEncendida,
  required ValueChanged<bool> onToggleApp,
}) {
  return buildTarjeta(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    "Línea Privada",
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  SizedBox(height: 2),
                  Text(
                    "Interruptor general del bloqueador",
                    style: TextStyle(fontSize: 12, color: Colors.grey),
                  ),
                ],
              ),
            ),
            // Switch de encendido / apagado de la aplicación
            Switch(
              value: appEncendida,
              activeTrackColor: Colors.teal,
              inactiveThumbColor: Colors.grey,
              onChanged: onToggleApp,
            ),
          ],
        ),
        const SizedBox(height: 6),
        Text(
          !appEncendida
              ? "🔌 APAGADA: el teléfono opera normal. El filtro no interviene."
              : "🤖 Modo Automático desatendido: bloquea y filtra spam, números desconocidos e internacionales.",
          style: const TextStyle(fontSize: 13, color: Colors.grey),
        ),
      ],
    ),
  );
}
