# MagnetRute v0.3 – Debug-APK über GitHub Actions

Der Workflow `.github/workflows/android-apk.yml` läuft automatisch bei jedem Push
(Branches und Tags), bei Pull Requests und manuell über **Actions → MagnetRute
Android APK → Run workflow**. Android Studio ist für diesen Download nicht nötig.
Bei Pull Requests aus Forks kann GitHub die Freigabe eines Maintainers verlangen.
Ein Push auf einen Branch mit offenem Pull Request kann zwei Läufe erzeugen.

## APK herunterladen

1. Im Repository **Actions → MagnetRute Android APK** öffnen.
2. Den gewünschten erfolgreichen Lauf auswählen. Bei Pull Requests wird der
   von GitHub erzeugte Merge-Stand getestet.
3. Unter **Artifacts** das Artefakt herunterladen und dessen ZIP entpacken.
4. Die enthaltene `.apk` auf ein Android-Gerät ab Android 8 (API 26) kopieren und
   installieren; gegebenenfalls die Installation aus dieser Quelle erlauben.

Die Namen enthalten App-Version, Laufnummer, Wiederholungsnummer und kurzen
Commit-Hash, zum Beispiel:

```text
MagnetRute_v0.3.0_debug_42.1_abcdef0.apk
MagnetRute_v0.3.0_debug_42.1_abcdef0.apk.sha256
```

Die Version wird aus `app/build.gradle` über `:app:printVersion` gelesen.
Ein Tag `v0.3.0` muss dazu passen; bei abweichenden `v*`-Tags schlägt der Lauf fehl.
Der Workflow erstellt Download-Artefakte, keine GitHub Releases. Die Artefakte
werden für 30 Tage gespeichert (organisationsweite GitHub-Limits können dies
verkürzen). Den APK-Download daher für eine längerfristige Sicherung lokal ablegen.

## Was wird geprüft?

- JDK 17, Gradle 8.9, Android-Plattform 35 und Build-Tools 34/35 werden eingerichtet.
- `bash tests/run_tests.sh` führt die Android-unabhängigen Tests aus.
- `:app:testDebugUnitTest` prüft die Android-Bildverarbeitung mit Robolectric.
- `:app:lintDebug` prüft den Android-Quellcode; Fehler verhindern die APK-Ausgabe.
- `:app:assembleDebug` erstellt die Debug-APK.
- Zur APK wird eine SHA-256-Datei bereitgestellt. Unter Linux lässt sie sich aus
  dem entpackten Verzeichnis mit `sha256sum -c *.apk.sha256` prüfen.
- Lint-Berichte erscheinen als eigenes Artefakt, auch bei einem Lint-Fehler,
  sofern der Lauf die Berichte erstellen konnte.

Lokal entsprechen die wesentlichen Prüfungen:

```bash
bash tests/run_tests.sh
bash gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
bash gradlew --no-daemon --console=plain -q :app:printVersion
```

## Debug-Signatur und Updates

Dies ist eine Debug-APK für Tests und manuelle Installation. Jeder frische
GitHub-Runner erzeugt einen eigenen Debug-Signaturschlüssel. Eine APK aus einem
späteren Lauf lässt sich daher möglicherweise nicht über eine vorhandene
Installation installieren. **Vor einer Deinstallation die Grundrisse als ZIP und
die Messprotokolle exportieren**, weil private App-Daten dabei gelöscht werden.
Für dauerhaft installierbare Updates wäre ein separat eingerichteter, stabiler
Signaturschlüssel erforderlich. Der Workflow braucht keine Signing-Secrets.

## Fehlersuche und Datenschutz

Bei einem roten Lauf den fehlgeschlagenen Schritt und gegebenenfalls das
Lint-Artefakt öffnen. Sind keine APK-Artefakte vorhanden, zuerst die Testergebnisse
und den Build prüfen. Ein GitHub-Artefakt ist ein Download-ZIP mit der APK darin.

Die App benötigt keinen Internetzugriff. Der Build lädt nur Werkzeuge und
Abhängigkeiten; private Fotos, Grundrisse und Messprotokolle werden dabei nicht
auf GitHub hochgeladen. Standortdaten können in aktiv exportierten Messlogs
enthalten sein; Grundriss-ZIPs enthalten die gespeicherten Planbilder und Namen.
