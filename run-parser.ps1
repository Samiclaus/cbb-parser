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

# First try the JVM's normal truststore. This is sufficient on machines
# without TLS inspection (for example, without Zscaler).
$previousMavenOpts = $env:MAVEN_OPTS

try {
    Push-Location $projectRoot

    Write-Host "Starte Parser zunächst mit dem Java-Standard-Truststore..."
    $defaultOutput = @(& mvn clean package 2>&1)
    $defaultExitCode = $LASTEXITCODE
    $defaultOutput | ForEach-Object { Write-Host $_ }

    if ($defaultExitCode -eq 0) {
        Write-Host "Fertig. Ergebnisdatei: $(Join-Path $projectRoot 'clearwater-music.txt')"
        return
    }

    $defaultOutputText = $defaultOutput -join "`n"
    $isTlsTrustFailure = $defaultOutputText -match "PKIX path building failed|unable to find valid certification path|certificate_unknown"

    if (-not $isTlsTrustFailure) {
        throw "Maven-Build fehlgeschlagen (Exit-Code $defaultExitCode). Der Fehler sieht nicht nach einem TLS-Truststore-Problem aus; es erfolgt kein automatischer zweiter Versuch."
    }

    if (-not (Test-Path $ZscalerCertificatePath)) {
        throw @"
Der HTTPS-Zugriff ist mit dem Java-Standard-Truststore fehlgeschlagen, und es wurde kein Zscaler-Zertifikat gefunden:
$ZscalerCertificatePath

Wenn diese Umgebung Zscaler verwendet, lege dort das von deiner Organisation bereitgestellte und verifizierte Root-Zertifikat ab oder starte das Skript mit -ZscalerCertificatePath "C:\Pfad\zscaler.cer".
Ohne TLS-Inspektion sollte der Standard-Truststore ausreichen; prüfe in diesem Fall bitte die ursprüngliche Maven-Fehlermeldung.
"@
    }

    if (-not (Test-Path $javaTruststorePath)) {
        throw "Java-Standard-Truststore nicht gefunden: $javaTruststorePath"
    }

    # Build a fresh local copy only when the default connection failed
    # specifically because Java did not trust the TLS certificate chain.
    Copy-Item $javaTruststorePath $truststorePath -Force
    Write-Host "TLS-Trustfehler erkannt. Erstelle lokalen Truststore und importiere das verifizierte Zscaler-Zertifikat."
    & $keytoolPath -importcert -noprompt -alias $alias -file $ZscalerCertificatePath -keystore $truststorePath -storetype JKS -storepass $truststorePassword

    if ($LASTEXITCODE -ne 0) {
        throw "Der Import des Zscaler-Zertifikats ist fehlgeschlagen."
    }

    $truststoreArgument = '-Djavax.net.ssl.trustStore="' + $truststorePath + '"'
    $env:MAVEN_OPTS = (($previousMavenOpts, $truststoreArgument, "-Djavax.net.ssl.trustStorePassword=$truststorePassword", "-Djavax.net.ssl.trustStoreType=JKS" | Where-Object { $_ }) -join " ")

    Write-Host "Wiederhole Maven-Build mit lokal ergänzt­em Truststore..."
    & mvn clean package

    if ($LASTEXITCODE -ne 0) {
        throw "Maven-Build ist auch mit dem ergänzten Truststore fehlgeschlagen (Exit-Code $LASTEXITCODE)."
    }

    Write-Host "Fertig. Ergebnisdatei: $(Join-Path $projectRoot 'clearwater-music.txt')"
}
finally {
    Pop-Location -ErrorAction SilentlyContinue
    $env:MAVEN_OPTS = $previousMavenOpts
}
