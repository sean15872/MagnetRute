# MagnetRute v0.3.0 – virtuelle Wünschelrute / Android-Magnetfeldlabor

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

## Grundriss-Scanner (v0.3.0)

Im Messlabor **Grundriss-Scanner öffnen** wählen. Der Scanner arbeitet lokal, ohne Internet- oder Speicherberechtigung. Kamera und Dateiauswahl laufen über die installierten Android-Apps; eine Kamera-App ist nur für neue Aufnahmen nötig. Ohne Kamera lässt sich ein vorhandenes Planfoto öffnen.

1. **Papierplan fotografieren** oder **Planfoto aus Dateien öffnen**. Foto bei Bedarf mit **90° drehen** ausrichten. Gute Beleuchtung, ein flacher Plan und sichtbare Ecken verbessern das Ergebnis.
2. Vier nummerierte Ecken auf die Planecken ziehen: **1 oben links, 2 oben rechts, 3 unten rechts, 4 unten links**. **Perspektive entzerren** erzeugt eine rechteckige Arbeitskopie.
3. Ein Rechteck über den gewünschten Bereich ziehen oder dessen zwei Griffe verschieben. **Ausschnitt als Bereich speichern** öffnet Stockwerks- und Bereichsname (je 1–120 Zeichen), z. B. „Untergeschoss“ / „Heizraum“. Danach weitere Bereiche aus derselben entzerrten Arbeitskopie ausschneiden. Jeder Bereich wird sofort dauerhaft gespeichert.
4. Zur Bereichsliste wechseln und einen Bereich öffnen. **Maßstab kalibrieren**: zwei Endpunkte einer bekannten Strecke ziehen und deren reale Länge in Metern eingeben (z. B. `4,25`). Der Maßstab gilt nur für diesen Ausschnitt; Abmessungen und Meter/Pixel erscheinen in der Übersicht. Erneutes Kalibrieren ersetzt ihn.
5. Bereich und Stockwerk können umbenannt werden. Beim Umbenennen eines Stockwerks werden alle Bereiche mit diesem bisherigen Stockwerksnamen gemeinsam geändert; gleiche Namen bilden dasselbe Stockwerk. Einzelne Bereiche lassen sich nach Bestätigung löschen.
6. **Grundrisse als ZIP sichern** speichert alle Bereiche, Stockwerksnamen und Kalibrierungen über die Android-Dateiauswahl. **Grundrisse aus ZIP wiederherstellen** prüft das Archiv und ersetzt erst nach Bestätigung die lokale Grundrisssammlung. Messlogs bleiben erhalten. Vor einem Austausch die bisherige Sammlung sichern.

Das Grundriss-ZIP enthält `manifest.properties` (UTF-8, Schema 1) und JPEGs unter `images/<UUID>.jpg`. Es ist vom Messlog-ZIP getrennt. Importiert werden ausschließlich diese Grundriss-ZIPs, keine beliebigen Foto-ZIPs oder PDF-Dateien. Bilder werden vor dem Übernehmen dekodiert und mit den Metadaten abgeglichen. Fehlgeschlagene Importe verändern das lokale Archiv nicht; lokale Speichervorgänge ersetzen es atomar.

Grenzen: maximal 30 Bereiche, 8 MiB pro JPEG und 40 MiB Bilddaten insgesamt. Fotoimport maximal 30 MiB / 120 Megapixel; Arbeitskopien werden auf maximal 2000 Pixel Kantenlänge begrenzt und ohne Foto-EXIF als JPEG gespeichert. ZIP-Import unterstützt Bilder bis 2400 Pixel. Das ursprüngliche Foto wird nicht in der Sicherung mitgeführt. Maßstab und Entzerrung sind manuelle Hilfsmittel, kein vermessungstechnischer Genauigkeitsnachweis; verzogene Pläne und stark schräge Aufnahmen können die Proportionen verfälschen.

Grundrisse erhalten **keine automatische GPS- oder Magnetfeldzuordnung**. Beim Wechsel vom Messlabor zum Scanner pausieren Sensor-/GPS-Empfang wie bei anderen Appwechseln; die bestehende Sitzung protokolliert Pause/Fortsetzung. Für Messungen zum Messlabor zurückkehren. Grundriss-ZIPs können sensible Gebäudeinformationen enthalten.

## Entwicklung und Tests

JDK 17, Android SDK Platform 35 und Build-Tools 34.0.0/35.0.0 sowie Gradle 8.9 verwenden. In der eingerichteten Cloud-Umgebung zuerst `source /workspace/.magnetrute/activate.sh` ausführen. Im Repository:

```sh
bash tests/run_tests.sh
bash gradlew --no-daemon --max-workers=4 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Der Shell-Testlauf prüft Sensormathematik, Perspektivgeometrie, Kalibrierung und den abgesicherten ZIP-Import. Android-Tests mit Robolectric prüfen Bildoperationen und bestehende Logfunktionen ohne angeschlossenes Handy. Beim ersten Lauf werden Testabhängigkeiten heruntergeladen. Berichte liegen unter `app/build/reports/`. Kamera-App, Berührungsgesten, reale Sensoren und GPS zusätzlich auf einem Android-Gerät (API 26+) testen.

## GPS / Standort-Kartierung (seit v0.2.0)

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
2. **Android Studio** öffnen → **Open** → Verzeichnis `MagnetRute_v0.3` wählen (den Ordner, in dem `settings.gradle` liegt).
3. Falls eine Gradle-Abfrage kommt: JDK 17 oder 21 und Android SDK Platform 35 installieren/zulassen. Gradle 8.9 wird beim ersten Start über `gradlew` bzw. `gradlew.bat` von der offiziellen Gradle-Seite mit SHA-256-Prüfung geladen. Das Projekt verwendet Android Gradle Plugin 8.7.3. Internet ist für den ersten Abgleich nötig.
4. Android-Handy mit USB-Debugging anschließen → **Run** (grünes Dreieck) ODER **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Gebaute Debug-APK: `app/build/outputs/apk/debug/app-debug.apk`. Beim Kopieren auf Handy nötigenfalls Installation aus dieser Quelle zulassen.

**Debug-APK:** Der Build erzeugt `app/build/outputs/apk/debug/app-debug.apk`; GitHub Actions stellt versionierte Artefakte bereit. Tests auf echter Android-Hardware sind zusätzlich erforderlich.

### Gradle-Hinweis

Aus technischen Gründen enthält dieses Paket keine offizielle `gradle-wrapper.jar`, sondern schlanke Startskripte `gradlew` / `gradlew.bat`. Sie laden Gradle **8.9 von services.gradle.org** herunter und prüfen vor dem Entpacken dessen offizielle SHA-256-Summe. Du kannst alternativ in Android Studio eine bereits vorhandene Gradle-8.9-Installation auswählen.

Für den manuellen Build auf Linux Mint:

```sh
cd MagnetRute_v0.3
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
- `PlanActivity.java` / `PlanEditorView.java`: Grundrisssammlung, Foto- und Touch-Editor
- `PlanImages.java` / `PlanGeometry.java`: Bilddekodierung, Entzerrung und Maßstab
- `PlanArchive.java`: validierte ZIP-Sicherungen mit atomarer Speicherung
- `PlanCaptureProvider.java`: begrenzte Kamera-Freigabe für genau ein Arbeitsfoto

## Versionskonzept

Name und Versionsangabe bewusst eindeutig: `MagnetRute_v0.3` / `0.3.0`. Diese Version hat `versionCode 3`, `versionName '0.3.0'`. Künftige Versionen bekommen eine höhere Versionsnummer.
