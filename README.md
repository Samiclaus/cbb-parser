# Clearwater Community Band Parser

A small Java 17 / Maven application that scans the numbered music-template pages on the
[Clearwater Community Band website](https://www.clearwatercommunityband.com/copy-of-music-template)
and writes the extracted h3 and h4 headings to clearwater-music.txt.

## Requirements

- JDK 17 or newer
- Maven 3.8 or newer

## Build and run

On a standard connection, run:

~~~powershell
mvn clean package
~~~

The Maven package phase runs the parser and writes clearwater-music.txt into the
project's current directory. The same results are also printed to the console.

### Windows with Zscaler HTTPS inspection

If Java reports PKIX path building failed while Windows browsers or curl.exe can open
the site, your network may be inspecting HTTPS with a Zscaler certificate that is not in
Java's truststore.

Use the included PowerShell script:

~~~powershell
.\run-parser.ps1
~~~

By default, the script looks for the organization's Zscaler root certificate at
$env:USERPROFILE\zscaler.cer. To use another path:

~~~powershell
.\run-parser.ps1 -ZscalerCertificatePath "C:\path\to\zscaler.cer"
~~~

On first run, the script copies the active JDK's standard cacerts store to the local
parser-truststore.jks and imports the Zscaler root certificate under the alias
zscaler-root. It then runs Maven using that local truststore. If the local truststore
already exists, the script reuses it and imports the certificate only if the alias is
missing.

**Security notes:**

- Obtain the root certificate from a trusted organizational source and verify its
  SHA-256 fingerprint before trusting it. Do not import a certificate merely because a
  website presented it.
- The script does not disable TLS verification.
- The local truststore is intentionally excluded from Git. It can contain organization-
  specific trust settings and must be recreated on another machine.
- The script assumes the copied Java truststore uses the usual password changeit.
  If your JDK's truststore password differs, adjust the script accordingly.
- If PowerShell blocks script execution, run it from a permitted PowerShell session or
  follow your organization's execution policy; do not weaken machine-wide security policy
  just for this script.

To run Maven manually after setting up the truststore in the current PowerShell session:

~~~powershell
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStore=$PWD\parser-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=JKS"
mvn clean package
~~~

## Parser behavior

- Starts at copy-of-music-template-1, then checks numbered pages in ascending order.
- Fetches up to 10 pages concurrently.
- Stops after three consecutive HTTP 404 responses.
- Extracts distinct h3 and h4 headings per page.
- Logs non-200 responses and request errors to the output file.

The website may change its page structure, so the output should be reviewed if the site is updated.

## Optional settings

These Java system properties can be supplied to Maven's exec plugin:

- ccb.maxPage: maximum page number (default 999)
- ccb.concurrency: number of concurrent requests (default 10)
- ccb.consecutive404Limit: consecutive 404s before stopping (default 3)
- ccb.output: output path (default clearwater-music.txt)

For example:

~~~bash
mvn -Dccb.maxPage=100 -Dccb.concurrency=5 clean package
~~~
