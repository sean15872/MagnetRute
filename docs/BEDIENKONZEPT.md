# MagnetRute – verbindliche Bedienlogik (v0.4.1)

## Grundsatz

**Das Gerät dient zuerst der Messung und einer einfachen Grundstückskarte.** Es ist keine GIS-Fachsoftware. Jede Ansicht zeigt nur die nächste sinnvolle Aktion. Fortgeschrittene Funktionen liegen in **Mehr** und dürfen den Erststart nicht versperren. Keine obligatorische Anleitung.

## Erfolgsweg für Erstbenutzer

1. **Karte öffnen**: prominent sichtbar **Ort suchen**, **Standort holen**, **Luftbild laden** und **Mehr**. Noch kein Grundstück vorhanden? Keine Fehlermeldung „Grundstück auswählen“ bei diesen Aktionen.
2. **Ort finden**: Eingabe etwa `Rinegg 69, 8853 Ranten` oder GPS-Standort wählen. Die Karte zentriert sich auf den Fund und lässt sich mit Gesten verschieben, drehen und zoomen. Die ausdrücklich ausgelöste Suche darf online sein; ohne Einwilligung kein verdecktes Nachladen.
3. **Grundstück einzeichnen**: Erst NACH der Kartenwahl den großen Button **+ Grundstück einzeichnen** drücken. Die Karte übernimmt genau die gewählte Ansicht. Drei oder mehr Eckpunkte per Fingertipp, sichtbare Griffe zum Verschieben, **Letzten Punkt zurück**, **Fertig** oder **Abbrechen**. Danach fragt die App den frei wählbaren Namen ab.
4. **Offline vorbereiten**: Nach dem Speichern erscheint als Hauptaktion **Offline-Karte herunterladen**. Kartenausschnitt, Kacheln und Größe bestätigen. Fertige Pakete bleiben ohne Netzwerk nutzbar. Kein heimlicher Online-Fallback.
5. **Begehung starten**: Erst danach treten **Aufnahme starten**, **Stopp**, **+ Markierung** und die Ansichtsschalter **Karte / Messspur / Heatmap** in den Vordergrund. Marker sollen auch manuell auf der Karte gesetzt oder korrigiert werden können. GPS-Genauigkeit sichtbar halten.

## Status, Dauer und Signale

- **Aufnahme läuft**: große, eindeutig rote Statuszeile, Aufnahmeindikator und laufende Zeit im Format mm:ss. Knopf **PAUSE**.
- **Pausiert**: gelbe Statuszeile, deutlich sichtbares **FORTSETZEN** und **Stopp**.
- **Gestoppt**: Abschlusszeit sichtbar; für v0.4.1 steht die Zeit **seit Sitzungsbeginn einschließlich Pausen**, nicht eine erfundene reine Sensoraufnahmedauer.
- Zwischen Kartenansicht und Messlabor unterscheiden: vorhandenes Messlabor-Logging nicht stillschweigend durch Begehungsservice ersetzen.
- Messwerte, Referenz, Kalibrierungs-/GPS-Qualität und Export unverfälscht beibehalten.

## Kartenmaßstab

Kartendienste liefern bis zu einer real verfügbaren Detailstufe. Das UI darf bis **Zoom 22** weiter vergrößern, zeichnet dann aber vergrößerte Quellkacheln, **keine zusätzlichen Luftbilddetails**. GPS-Unsicherheit ändert sich dadurch nicht. Visuelle Übervergrößerung kenntlich machen.

## Abnahmetests auf dem Smartphone

- Neue Installation, kein Grundstück: Suche oder Standort ohne Voraussetzungen, keine leere Projekt-Pflicht.
- Ort suchen → zoomen → drei Ecken → Fertig → Name → Offline speichern → Flugmodus → Karte wieder öffnen.
- Ein Finger verschiebt, zwei Finger zoomen und drehen, Zoomen über Stufe 19 möglich.
- Neue Markierung mit Kategorie **Baum**, **Drainage** etc. und manuelle Lagekorrektur.
- Messung starten, Ansichten wechseln, Pause, Fortsetzen, Stopp; Aufnahmezustand und Zeit jederzeit eindeutig.
- Alle bisherigen Messungen, Projekt-ZIPs, Offline-Karten und Grundrisse bleiben erhalten.
- Fehler dürfen nicht nur als "kein Grundstück" erscheinen; fehlender Zustand führt direkt zur passenden Aktion.
- Auf kleiner Smartphonebreite keine horizontale Leiste mit 15 versteckten Standardaktionen.

## Für weitere Codex-Arbeiten

Keine eigenständigen UX-Umgestaltungen ohne konkrete Freigabe. Neue Features ergänzen nur die entsprechenden Schrittansichten oder das **Mehr**-Menü. Vor jedem neuen Button prüfen, ob der Benutzer diese Aktion in der jeweiligen Phase überhaupt braucht. Bestehende Projektdateien und lokale Messwerte nie ohne Sicherungs- und Migrationskonzept löschen.
