# MagnetRute v0.2.0 – virtuelle Wünschelrute / Android-Magnetfeldlabor

**Android-App mit Magnetometer-Visualisierung, lageunabhängigen Magnetfeldwerten, optionalem GPS und ZIP-Diagnoselog.**

## Funktionsumfang

- Zwei animierte virtuelle Wünschelruten, deren Ausschlag auf dem **Betrag einer messbaren Magnetfeldabweichung** beruht. Symbolische Darstellung, kein Messnachweis für Wasseradern!
- Original X/Y/Z des 3-Achsen-Magnetometers, Gesamtbetrag |B| in µT. Zusätzlich im CSV, sofern vorhanden: unkalibrierte X/Y/Z samt den drei Kalibrier-Bias-Werten.
- Vertikalanteil als Projektion auf die Schwerkraftrichtung und horizontaler Betrag dazu senkrecht. Diese Aufteilung ist weitgehend lageunabhängig, solange das Handy nicht stark beschleunigt wird und der Gravitationsermittler korrekt arbeitet.
- Referenz setzen: Mittelwert der jüngsten maximal 30 Messwerte (ca. 3 s). Auch automatische Referenz nach etwa 2 s.
- Umschaltbare 60-Sekunden-Grafik: ±Δ zur Referenz oder Magnetometer X/Y/Z/|B|.
- Empfindlichkeit einstellbar zwischen 0,5 und 20,0 µT.
- Logstart/-stopp; Logdatei enthält Rohdaten, abgeleitete Komponenten, gleitenden Messwert, Referenz, Delta, Bewegungsindikator, Sensorgenauigkeit, Zeitstempel.
- Manuelle Markierungen (z.B. "1 m vor Kamin"). Automatische Erfassung von deutlichen Ausschlägen.
- Teilbarer **MagnetRute_Log.zip** oder „Log als ZIP speichern“ über die Android-Dateiauswahl mit `messwerte.csv`, `standorte.csv`, `ereignisse.csv`, `kartenpunkte.geojson` und `diagnose.txt`.
- Keine Werbung, keine Internetberechtigung, keine Cloud-Übertragung. Standortfreigabe ist **optional und beim Start ausgeschaltet**; die App misst auch ohne GPS.

## GPS / Standort-Kartierung (neu v0.2.0)

- Schalter **GPS-Standort mit aufzeichnen** ist beim Appstart standardmäßig AUS. Erst beim Einschalten werden die Android-Berechtigungen für genauen/ungefähren Vordergrundstandort angefragt.
- Erlaubst du „Genau“, kann GPS genutzt werden, sofern der GPS-Anbieter aktiv ist. Bei „Ungefähr“ ist die georeferenzierte Kartierung deutlich ungenauer, die Magnetmessung funktioniert dennoch.
- Während die App **sichtbar** ist, werden GPS- und Netzwerk-Fixes empfangen. Keine Hintergrundortung und keine Internetfreigabe.
- Während einer Aufnahme enthält `messwerte.csv` jede Magnetfeldmessung mit **letztem gültigen Standortfix** (maximal 5 Sekunden alt), `gps_accuracy_m`, `gps_fix_unix_ms`, `gps_fix_elapsed_ns`, `gps_age_ms`. Fehlt ein aktueller Standort, sind alle GPS-Felder leer; es werden keine Koordinaten erfunden oder interpoliert.
- `standorte.csv` enthält **alle einzelnen Ortungs-Fixes**, auch wenn kein Magnetfeldereignis eintritt. Provider `gps` / `network`, Genauigkeit und Zeitstempel werden gesichert.
- `ereignisse.csv` enthält optionale Koordinaten und Genauigkeit zu Referenzen, Markierungen und Ausschlägen.
- `kartenpunkte.geojson`: Geopunkte für manuelle MARKIERUNG sowie Beginn/Ende automatischer Ausschläge. Die Datei lässt sich in QGIS oder GeoJSON-kompatible Karten importieren. GeoJSON hat den Standard WGS84, Koordinatenfolge **Längengrad, Breitengrad**.
- GPS-Genauigkeit in Gebäuden häufig schlecht (oder kein Fix). **Für den nahen Bereich eines Kamins ist GPS nicht metergenau zuverlässig.** Später sind lokale Koordinaten eines Gebäudegrundrisses, Wegpunkte oder eine manuelle Positionsmarkierung sinnvoll.
- **Datenschutz:** geteilte ZIP-Dateien können deinen genauen Standort preisgeben. Vor dem Versenden bewusst prüfen. Du kannst GPS jederzeit abschalten; vorher gespeicherte Koordinaten derselben Sitzung bleiben jedoch im Log.

## APK ohne Android Studio direkt über GitHub bauen

Dieses Paket ist für **GitHub Actions** vorbereitet: `.github/workflows/android-apk.yml`.
Nach dem Hochladen der Projektdateien in ein GitHub-Repository wird automatisch eine
installierbare Debug-APK gebaut und unter **Actions → Artefakte** bereitgestellt.
Die genaue Schritt-für-Schritt-Anleitung steht in [`GITHUB_ANLEITUNG.md`](GITHUB_ANLEITUNG.md).

## Android Studio: Installieren / APK bauen

1. ZIP auf PC entpacken.
2. **Android Studio** öffnen → **Open** → Verzeichnis `MagnetRute_v0.2` wählen (den Ordner, in dem `settings.gradle` liegt).
3. Falls eine Gradle-Abfrage kommt: JDK 17 oder 21 und Android SDK Platform 35 installieren/zulassen. Gradle 8.9 wird beim ersten Start über `gradlew` bzw. `gradlew.bat` von der offiziellen Gradle-Seite mit SHA-256-Prüfung geladen. Das Projekt verwendet Android Gradle Plugin 8.7.3. Internet ist für den ersten Abgleich nötig.
4. Android-Handy mit USB-Debugging anschließen → **Run** (grünes Dreieck) ODER **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Gebaute Debug-APK: `app/build/outputs/apk/debug/app-debug.apk`. Beim Kopieren auf Handy nötigenfalls Installation aus dieser Quelle zulassen.

**Wichtig:** Diese ZIP enthält Quellcode und Builddateien, aber **keine fertige APK**, weil in der Erstellungsumgebung kein Android SDK/Gradle zum APK-Bau vorhanden war. Nicht als getestet auf echter Android-Hardware betrachten.

### Gradle-Hinweis

Aus technischen Gründen enthält dieses Paket keine offizielle `gradle-wrapper.jar`, sondern schlanke Startskripte `gradlew` / `gradlew.bat`. Sie laden Gradle **8.9 von services.gradle.org** herunter und prüfen vor dem Entpacken dessen offizielle SHA-256-Summe. Du kannst alternativ in Android Studio eine bereits vorhandene Gradle-8.9-Installation auswählen.

Für den manuellen Build auf Linux Mint:

```sh
cd MagnetRute_v0.2
./gradlew assembleDebug
```

## Erster Feldtest am Kamin

1. Handy von Schlüsselbund, Magneten, Lautsprechern und starken Stromkabeln fernhalten. Schutzhülle mit Magnetverschluss entfernen.
2. Handy 1–2 m vom Kamin stabil halten, 2 Sekunden stabilisieren lassen; **Neue Referenz setzen**.
3. **Aufzeichnen** drücken. **Markierung**: „Start 2 m“, dann langsam herangehen; wieder markieren bei 1 m, 0,5 m, direkt davor.
4. Geradeaus wieder zurückgehen, das Handy möglichst nicht drehen. Test mindestens dreimal wiederholen.
5. **Vor der Aufnahme** bei gewünschter Kartierung den GPS-Schalter aktivieren und auf einen gültigen Fix warten. Danach **Aufnahme stoppen**, dann **Log teilen** → ZIP über die Android-Teilen-Funktion übergeben; alternativ **Log als ZIP speichern** und die Datei direkt im Chat anhängen.
6. Kontrollversuch an einer anderen Metalloberfläche bzw. einem Kamin ohne relevante ferromagnetische Teile, wenn vorhanden. Vergleich mit entfernter magnetischer Handyhülle.

Bedeutung der Anzeigen: Die X/Y/Z-Achsen sind **im Handy** definiert und drehen sich mit dem Gerät. |B| (Vektorbetrag) bleibt bei idealem Sensor unter reiner Rotation gleich. In der Praxis können Kalibrierung, ferromagnetische Gegenstände und dynamische Bewegung den Messwert beeinflussen.

## Export-Dateien

- `messwerte.csv`: UTF-8, Komma getrennt, **Dezimalpunkt**; etwa 10 Messungen pro Sekunde. Fehlende Werte = leeres Feld.
- `ereignisse.csv`: ISO-Zeit, Zeit seit Beginn, Ereignis, Kommentar (z.B. `MARKIERUNG`, `AUSSCHLAG_BEGIN`) plus GPS-Position/Genauigkeit, sofern freigegeben.
- `standorte.csv`: jeder empfangene Standort-Fix mit Datum, Koordinaten, GPS-Genauigkeit, Anbieter und monotonichem Zeitstempel.
- `kartenpunkte.geojson`: GeoJSON-Kartenpunkte für markierte/automatisch erkannte Stellen mit vorhandenem GPS-Fix.
- `diagnose.txt`: Handy-, Sensor- und GPS-Aufnahme-Statistik; enthält selbst keine Koordinaten.

Die App zeichnet **nur während geöffneter App** auf; beim Wechsel in den Hintergrund pausieren die Sensoren. Im Log werden `APP_PAUSE` und `APP_RESUME` erfasst. Bei regulärem App-Schluss wird die Aufnahme beendet.

## Besonderheiten / Grenzen

- Die magnetischen Komponenten werden aus Magnetometer und TYPE_GRAVITY abgeleitet, ersatzweise accelerometerbasierter Tiefpass. **Keine echte magnetische Nord-/Ost-Projektion**, weil das Magnetfeld selbst gerade das Störsignal darstellt.
- Ein verändertes Magnetfeld kann z.B. durch Stahlarmierungen, Kaminbauteile, Eisen, elektrische Ströme, Handy-Magnete oder die Autokalibrierung entstehen. Es beweist weder Grundwasser noch einen spezifischen Untergrundstoff.
- Fehlende Sensoren: Kein Magnetometer → App kann nicht messen. Kein Gravitations-/Beschleunigungssensor → nur Gesamtbetrag zuverlässig verfügbar.
- Kein Hintergrundservice; Standortaufzeichnung pausiert ebenfalls beim Appwechsel. Kein dauerhafter Handy-Speicherzugriff. Logs bleiben privat gespeichert; Nutzer teilt sie aktiv.
- Die App ist ein **experimenteller Prototyp** (v0.2.0); noch nicht auf echter Hardware getestet.

## Quellcode

`app/src/main/java/at/andi/magnetrute/`:

- `MainActivity.java`: UI, Sensorevents, Referenz, Erkennung, Share
- `SensorMath.java`: berechenbare geometrische Feldkomponenten (Android-frei)
- `RodView.java`: die animierten Ruten
- `GraphView.java`: Graph-Ringpuffer
- `MeasurementLogger.java`: CSV, GPS-Fixes, Ereignisse, GeoJSON, ZIP
- `LogExportProvider.java`: zeitlich freigegebener read-only-Logexport

## Versionskonzept

Name und Versionsangabe bewusst eindeutig: `MagnetRute_v0.2` / `0.2.0`. Diese Version hat `versionCode 2`, `versionName '0.2.0'`. Künftige Versionen bekommen eine höhere Versionsnummer.
