param(
    [string]$ZscalerCertificatePath = (Join-Path $env:USERPROFILE "zscaler.cer")
)

$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$truststorePath = Join-Path $projectRoot "parser-truststore.jks"
$keytoolPath = Join-Path $env:JAVA_HOME "bin\keytool.exe"
$javaTruststorePath = Join-Path $env:JAVA_HOME "lib\security\cacerts"
$truststorePassword = "changeit"
$alias = "zscaler-root"

if (-not (Test-Path $keytoolPath)) {
    throw "keytool.exe wurde nicht gefunden. Bitte JAVA_HOME prüfen: $env:JAVA_HOME"
}

if (-not (Test-Path $javaTruststorePath) -and -not (Test-Path $truststorePath)) {
    throw "Java-Standard-Truststore nicht gefunden: $javaTruststorePath"
}

if (-not (Test-Path $truststorePath)) {
    Copy-Item $javaTruststorePath $truststorePath
    Write-Host "Java-Standard-Truststore kopiert: $truststorePath"
}

# Check whether the Zscaler root is already in the local truststore.
& $keytoolPath -list -keystore $truststorePath -storetype JKS -storepass $truststorePassword -alias $alias *> $null
$aliasExists = ($LASTEXITCODE -eq 0)

if (-not $aliasExists) {
    if (-not (Test-Path $ZscalerCertificatePath)) {
        throw @"
Das Zertifikat mit Alias '$alias' fehlt im lokalen Truststore und wurde nicht gefunden:
$ZscalerCertificatePath

Lege das von deiner Organisation bereitgestellte und verifizierte Root-Zertifikat dort ab
oder starte das Skript mit -ZscalerCertificatePath "C:\Pfad\zscaler.cer".
"@
    }

    Write-Host "Importiere verifiziertes Zscaler-Root-Zertifikat aus: $ZscalerCertificatePath"
    & $keytoolPath -importcert -noprompt -alias $alias -file $ZscalerCertificatePath -keystore $truststorePath -storetype JKS -storepass $truststorePassword

    if ($LASTEXITCODE -ne 0) {
        throw "Der Import des Zscaler-Zertifikats ist fehlgeschlagen."
    }
}
else {
    Write-Host "Zscaler-Zertifikat ist bereits im lokalen Truststore vorhanden."
}

$previousMavenOpts = $env:MAVEN_OPTS
$truststoreArgument = '-Djavax.net.ssl.trustStore="' + $truststorePath + '"'
$env:MAVEN_OPTS = (($previousMavenOpts, $truststoreArgument, "-Djavax.net.ssl.trustStorePassword=$truststorePassword", "-Djavax.net.ssl.trustStoreType=JKS" | Where-Object { $_ }) -join " ")

try {
    Push-Location $projectRoot
    Write-Host "Starte Parser mit Maven..."
    & mvn clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven-Build fehlgeschlagen (Exit-Code $LASTEXITCODE)."
    }
}
finally {
    Pop-Location -ErrorAction SilentlyContinue
    $env:MAVEN_OPTS = $previousMavenOpts
}

Write-Host "Fertig. Ergebnisdatei: $(Join-Path $projectRoot 'clearwater-music.txt')"
