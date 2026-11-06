# =============================================================================
# Construye el modulo Magisk "LineaPrivada_Privileged"
# 1) Compila el APK release (si no existe o si se pasa -build)
# 2) Lo copia a /system/priv-app/LineaPrivada/LineaPrivada.apk
# 3) Genera LineaPrivada_Privileged.zip listo para instalar desde Magisk
# =============================================================================

param(
    [switch]$build = $true
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$moduleDir = Join-Path $root "magisk\LineaPrivada_Privileged"
$apkSrc = Join-Path $root "build\app\outputs\flutter-apk\app-release.apk"
$apkDst = Join-Path $moduleDir "system\priv-app\LineaPrivada\LineaPrivada.apk"
$zipOut = Join-Path $root "magisk\LineaPrivada_Privileged.zip"

# --- 1) Compilar el APK ---
if ($build) {
    Write-Host "==> Compilando APK release..."
    Push-Location $root
    try {
        & flutter build apk --release
        if ($LASTEXITCODE -ne 0) { throw "flutter build fallo" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $apkSrc)) {
    throw "No se encontro el APK: $apkSrc"
}

# --- 2) Copiar el APK al modulo ---
Write-Host "==> Copiando APK al modulo..."
Copy-Item -LiteralPath $apkSrc -Destination $apkDst -Force

# --- 3) Crear el zip (la raiz del zip debe ser module.prop) ---
if (Test-Path -LiteralPath $zipOut) { Remove-Item -LiteralPath $zipOut -Force }
Write-Host "==> Creando zip: $zipOut"
Compress-Archive -Path (Join-Path $moduleDir "*") -DestinationPath $zipOut -Force

Write-Host ""
Write-Host "LISTO: $zipOut"
Write-Host ""
Write-Host "Instalacion:"
Write-Host "  1) Copia LineaPrivada_Privileged.zip al telefono"
Write-Host "  2) Abre Magisk -> Modulos -> Instalar desde almacenamiento"
Write-Host "  3) Reinicia el telefono"
Write-Host "  4) Verifica: Permisos -> Linea Privada -> 'Llamadas de voz' concedido"
Write-Host ""
