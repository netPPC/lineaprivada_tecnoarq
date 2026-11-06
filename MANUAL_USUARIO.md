# Linea Privada TecnoArqueologIC — Manual de Usuario (Beta)

App de filtrado y bloqueo de llamadas y SMS de desconocidos.

## Instalación

1. Copia el archivo `app-release.apk` al equipo.
2. Ábrelo y toca "Instalar" (acepta "Instalar apps desconocidas" si lo pide).
3. Primera apertura: concede los permisos que pida (teléfono, contactos, SMS, notificaciones).
4. Acepta el rol de **Teléfono por defecto** y de **Filtrado de llamadas** cuando el sistema lo ofrezca.
5. En la solicitud de **Accesibilidad**, actívala (opcional pero recomendada para el corte automático en equipos sin permisos de sistema).

## Uso básico

### Marcador (teclado)
- Abre el marcador con el botón **"Marcar número"** de la pantalla principal.
- Teclado: números 0-9, `*` y `#`. Botón `⌫` borra el último dígito (mantén presionado para limpiar todo).
- Botón verde **📞 Llamar**: inicia la llamada. Botón rojo **📵**: cuelga si hay llamada en curso.
- **Accesos directos** bajo el campo del número:
  - **👥 Contactos**: abre tu agenda de contactos.
  - **🕘 Perdidas**: muestra las llamadas perdidas en un diálogo (toca una para llamar).
  - **🔊 Altavoz**: activa o desactiva el manos libres.

### Duración de la llamada (teclado DTMF)
- Al estar en una llamada conectada, el teclado envía **tonos DTMF** para navegar menús automáticos (servicios de atención, IVR). Solo presiona la opción que necesites: 1, 2, 3...
- En la pantalla de llamada verás: **Altavoz**, **Silencio** y **Colgar**.

### Filtrado automático de desconocidos
- Las llamadas de números desconocidos **no aparecen en tu agenda ni están en la lista blanca** se cortan automáticamente tras ~5 tonadas.
- Las llamadas de tus contactos o números permitidos **sí suenan** normalmente.
- Puedes ver los bloqueos y los detalles en la pantalla principal de la app.

### SMS
- Los SMS de desconocidos se filtran y aparecen listados en la sección de mensajes filtrados de la app.

## Ajustes recomendados para una prueba en blanco

Para una marcha en blanco (sin cortar llamadas reales), deja el modo en **"Automático"** pero revisa:

1. Que tus contactos estén bien asignados (las llamadas de ellos suenan).
2. Prueba llamar desde otro equipo que **no esté en tu agenda** para ver el corte en ~5 tonos.
3. Prueba con un número en tu agenda para confirmar que no se corta.

## Solución de problemas

| Problema | Solución |
|---|---|
| La app ya no abre el marcador | Reapertura: toca el icono de la app y usa "Marcar número". |
| No corta llamadas desconocidas | Revisa que el rol de **Filtrado de llamadas** y **Accesibilidad** estén activos en Ajustes > Apps. |
| No llegan notificaciones | Concede el permiso de notificaciones y revisa el ahorro de batería (excluye la app). |
| Icono/letras raros | Asegúrate de instalar el APK más reciente. |

## Contacto

Beta de prueba — para reportar errores usa el registro del sistema o avisa al desarrollador con el detalle de lo ocurrido (qué hiciste, qué esperabas y qué pasó).