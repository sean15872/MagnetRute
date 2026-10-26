# MagnetRute – APK über GitHub Actions bauen

Das Projekt enthält bereits `.github/workflows/android-apk.yml`.
**Du brauchst kein Android Studio und kein Android SDK auf deinem PC.**

## Variante A: Im GitHub-Browser hochladen (ohne git-Befehle)

1. Auf https://github.com/new ein **privates** Repository namens `MagnetRute` erstellen.
   Kein README und keine .gitignore durch GitHub erzeugen lassen, weil beides in diesem Paket liegt.
2. Das ZIP **lokal entpacken**. Nicht die ZIP-Datei allein hochladen!
3. In deinem leeren Repository **uploading an existing file** oder
   **Add file → Upload files** öffnen.
4. Den **Inhalt** des entpackten `MagnetRute_v0.2`-Ordners hochladen,
   inklusive des versteckten Ordners `.github` (unter Linux mit `Strg+H` sichtbar machen).
   Die Dateien `settings.gradle`, `app/` und `.github/` müssen direkt im Repo-Stamm liegen.
5. **Commit changes** bestätigen. Unter **Actions → MagnetRute Android APK** startet
   der Build bei Upload auf `main` bzw. `master` automatisch. Alternativ **Run workflow**.
6. Den abgeschlossenen grünen Workflow anklicken. Unter **Artifacts**
   `MagnetRute_v0.2.0_APK` herunterladen, ZIP entpacken,
   `MagnetRute_v0.2.0_debug.apk` aufs Handy kopieren und installieren.

## Variante B: Mit Git (Linux)

```bash
cd MagnetRute_v0.2
# Nachdem du auf github.com/new dein neues privates Repository angelegt hast:
git init
git branch -M main
git add .
git commit -m "MagnetRute v0.2.0 mit automatischem APK-Build"
git remote add origin https://github.com/DEIN-GITHUB-NAME/MagnetRute.git
git push -u origin main
```

Dann wie oben den APK-Download unter **Actions → Artifacts** öffnen.

## Fehlersuche

- Kein Workflow sichtbar? Prüfen, ob `.github/workflows/android-apk.yml` wirklich im Repository liegt.
- Build rot? Auf den Lauf klicken → **Build debug APK** → roten Schritt aufklappen; Fehlermeldung hier posten.
- Artefakte sind nicht die APK direkt, sondern ein GitHub-Download-ZIP mit der APK darin.
- Dies ist eine **Debug-APK**. Sie ist für Tests und manuelle Installation gedacht,
  nicht als signierte Play-Store-Veröffentlichung.
- **Wichtig für spätere Updates:** GitHub erstellt bei jeder frischen Runner-Umgebung
  normalerweise einen anderen Debug-Signaturschlüssel. Deshalb kann eine neue Version
  möglicherweise nicht über die alte installiert werden. Vor dem Deinstallieren die
  Messprotokolle über die App exportieren. Für dauerhafte Updates sollte in einer
  Folgeversion ein festes, privat verwahrtes Release-Signing über GitHub Secrets hinzukommen.

## Sicherheit/Datenschutz

Die App selbst benötigt keinen Internetzugriff; Standortberechtigungen werden nur nach dem Einschalten der optionalen GPS-Funktion abgefragt. GPS-Koordinaten können in geteilten ZIP-Logs enthalten sein. Der
Workflow kompiliert nur den Quellcode; private Messlogs werden nicht auf GitHub übertragen.
Ein **privates** Repository ist für den Anfang sinnvoll.
