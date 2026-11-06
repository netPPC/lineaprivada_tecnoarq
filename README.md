# 🛡️ Linea Privada — TecnoarqueologiC

**Bloqueador y filtro de llamadas y SMS desconocidos + sincronización con base comunitaria.**

Aplicación Flutter con integración nativa Kotlin (Android) desarrollada por **TecnoarqueologiC Soluciones TI.**
Funciona en Android 9+ (incluye HyperOS/MIUI Xiaomi y EMUI/HarmonyOS Huawei).

## ✨ Funciones principales

| Función | Descripción |
|---|---|
| 🎛️ Modo Automático | Filtra y bloquea llamadas y SMS de forma desatendida (pantalla apagada) |
| 🔌 Interruptor general | Enciende/apaga toda la app de un toque (modo normal del teléfono) |
| 📵 Bloqueo automático | Ocultos, internacionales, Call Centers, prefijos quemados y ráfagas |
| ⏱️ Tope de ring | Una llamada desconocida suena máximo 7 tonadas y la app la cuelga |
| ☁️ Base comunitaria | Lista negra compartida en Google Sheets (CSV) con bloqueos y denuncias |
| 📩 Filtro SMS | Intercepta y almacena SMS de desconocidos y spam |
| 📋 Popup reactivo | Avisa la intercepción reciente al abrir la app y con notificación al desbloquear la pantalla |

## ⚙️ Modos de operación

- **Modo Automático:** las llamadas sospechosas (oculto, internacional, Call Center, base
  comunitaria, prefijo quemado) se bloquean solas. Las desconocidas suenan con tope de 7
  tonadas; si el número llama 6+ veces en 24h (o 6+ en 4h) queda bloqueado. Los SMS de
  desconocidos y spam se filtran a cuarentena.
- **Desactivado:** la app no interviene; todas las llamadas y SMS pasan por el sistema.

## 🧠 Reglas de bloqueo automático (FiltroNucleo.kt)

1. **Número oculto / privado / vacío** → bloqueo inmediato.
2. **Internacional** (no chileno, a menos que `permitir_internacionales`) → bloqueo inmediato.
3. **Estructura extraña / Call Center** (menos de 8 dígitos, más de 15, o prefijo de call center) → bloqueo.
4. **Match completo contra la base comunitaria** (CSV) → bloqueo.
5. **Prefijo quemado** (primeros 4–5 dígitos) → bloqueo.
6. **Ráfaga por número:** ≥6 llamadas del mismo número en 24h (o 6 en 4h) → bloqueo.
7. **Volumen de prefijo:** >5 números distintos con el mismo prefijo → se quema y bloquea.
8. **Contactos de la agenda y lista blanca** → pasan sin intervención.
9. **Desconocido sin reglas** → suena normalmente con **tope de 7 tonadas** (el servicio en
   primer plano cuelga la llamada si nadie contesta). Si se excede de la ráfaga, se bloquea
   en la siguiente llamada.

> Cada bloqueo automático se registra localmente y se marca como `pendiente_subir_drive_<numero>`
> para subirlo a la base comunitaria en la siguiente sincronización.

## 🔔 Popup de intercepción

- Al **abrir la app**, un popup muestra el último número bloqueado con su motivo (Desbloquear / Dejar bloqueado).
- Al **desbloquear la pantalla** (`PantallaReceiver`), se muestra una notificación heads-up con
  la intercepción reciente que abre la app al tocarla (best-effort en Android 10+ / capas HyperOS-EMUI).

## 📡 Sincronización con Google Drive / Sheets

- Hoja pública: `1JErCMdU95SFEVV0y3pnYiSPnxnUHC32cPPutVKU_tBc`
- CSV: `https://docs.google.com/spreadsheets/d/1JErCMdU95SFEVV0y3pnYiSPnxnUHC32cPPutVKU_tBc/export?format=csv&gid=0`
- La app descarga la lista negra y sube denuncias y bloqueos automáticos.

## 🗂️ Arquitectura

```
lib/
  main.dart                    # UI principal, ciclo de vida, popup de bloqueos recientes
  services/
    prefs_service.dart         # SharedPreferences + canal nativo
    drive_sync_service.dart    # Sincronización CSV + subida de pendientes
    call_handler_service.dart  # Puente de acciones nativas (desbloqueo, modo)
    sms_filter_service.dart    # Permisos y lectura de SMS filtrados
  ui/
    splash_screen.dart         # Pantalla de inicio original
    components.dart            # Widgets reutilizables
    panels.dart                # Panel de modo automático
android/app/src/main/kotlin/com/tecnoarq/lineaprivada_tecnoarq/
  MainActivity.kt              # Puente Flutter↔Kotlin, permisos, rol Call Screening
  FiltroNucleo.kt              # Motor de reglas unificado (llamadas + SMS)
  TecnoarqCallService.kt       # CallScreeningService (Android 10+)
  CallReceiver.kt              # Receptor PHONE_STATE (Android 9)
  SmsReceiver.kt               # Filtro SMS nativo
  FiltroServicio.kt            # Servicio en primer plano: tope de 7 tonadas y filtro vivo
  PantallaReceiver.kt          # Notificación heads-up al desbloquear la pantalla
  ArranqueReceiver.kt          # Reinicio automático tras BOOT_COMPLETED
  ContactosHelper.kt           # Consulta de la agenda
```

## 🔐 Permisos

`READ_PHONE_STATE`, `CALL_PHONE`, `PROCESS_OUTGOING_CALLS`, `READ_PHONE_NUMBERS`,
`READ_CALL_LOG`, `MANAGE_OWN_CALLS`, `READ_CONTACTS`, `RECEIVE_SMS`, `READ_SMS`,
`SEND_SMS`, `WRITE_SMS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL`,
`RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `VIBRATE`, `INTERNET`,
`ACCESS_NETWORK_STATE`, `MODIFY_PHONE_STATE` (privilegiado, para cortar la llamada por hardware).

> En HyperOS/EMUI la app requiere que se **desactive la optimización de batería** para funcionar
> desatendida. Para que el tope de 7 tonadas pueda cortar la llamada por hardware:
> `adb shell appops set com.tecnoarq.lineaprivada_tecnoarq MODIFY_PHONE_STATE allow`
> (o el módulo Magisk incluido en `magisk/LineaPrivada_Privileged.zip`).
> Sin ese privilegio, el corte de ring no se ejecuta, pero el bloqueo por ráfaga y las
> demás reglas siguen funcionando con normalidad.

## 🚀 Compilar la beta

```bash
flutter pub get
flutter build apk --release
# APK: build/app/outputs/flutter-apk/app-release.apk
```

## 📦 Notas de plataforma

- **iOS:** la app compila y la UI es compatible, pero el control de llamadas y SMS es
  exclusivo de Android (limitaciones del sistema iOS).
- **Android 9:** usa `CallReceiver` (PHONE_STATE) ya que `CallScreeningService` requiere API 29+.
- **Android 10+:** el filtrado de llamadas usa el rol de sistema `CallScreeningService`.

---
© TecnoarqueologiC Soluciones TI. · Beta 2.0

## Build (release)

`ash
flutter pub get
flutter build apk --release
flutter build appbundle --release
`

## CI

Workflow en .github/workflows/build.yml: instala Flutter/Java 17, ejecuta tests y compila APK de release.

## Releases

Las releases con APK/AAB se publican en la secci�n **Releases** del repositorio.
