# Zwift Click → MyWhoosh (macOS)

Ein kleines Programm für den Mac: Es verbindet sich per Bluetooth mit deinem
Zwift Click und schickt bei jedem Tastendruck die passende Taste an MyWhoosh:

| Click-Taste | MyWhoosh |
|---|---|
| **+** (rechte Seite) | **I** = hochschalten |
| **B** (rechte Seite) | **K** = runterschalten |
| **–** (linke Seite, nur mit `--both`) | **K** = runterschalten |

Der Click v1 (eine Taste + und eine Taste –) funktioniert ebenfalls.

## Warum nur die rechte Seite beim Click v2?

Zwift sperrt die **linke** Seite des Click v2 für andere Apps. Sie hört nach etwa
einer Minute auf, Tasten zu senden. Die **rechte** Seite ist nicht gesperrt.
Deshalb schaltet standardmäßig **+ hoch** und **B runter**, beides auf der
rechten Seite.

Wenn du die linke Seite trotzdem nutzen willst: Verbinde den Click vorher in der
Zwift-App (nicht Companion), schließe Zwift wieder und starte dann dieses
Programm mit `--both`. Laut BikeControl hält diese Freischaltung etwa 24 Stunden.

## Einrichtung (einmalig)

1. **Python 3 installieren**, falls noch nicht vorhanden: https://www.python.org/downloads/
2. Diesen Ordner auf den Mac holen, zum Beispiel per `git clone`.
3. Den Click **nicht** gleichzeitig mit MyWhoosh oder Zwift verbinden. Er kann
   nur mit einer App verbunden sein.

## Starten

Doppelklick auf **`start.command`**. Alternativ im Terminal:

```bash
cd zwift-click-bridge
./start.command            # normal
./start.command --both     # linke Seite auch nutzen
./start.command --scan     # nur anzeigen, welche Clicks in der Nähe sind
./start.command --debug    # alle Bluetooth-Nachrichten anzeigen (zur Fehlersuche)
```

Falls macOS meldet, dass die Datei nicht geöffnet werden darf: Rechtsklick auf
`start.command`, dann **Öffnen**. Oder im Terminal `bash start.command` ausführen.

Beim ersten Start fragt macOS nach zwei Berechtigungen für das **Terminal**:

- **Bluetooth**: erlauben.
- **Bedienungshilfen** (Systemeinstellungen → Datenschutz & Sicherheit →
  Bedienungshilfen): Terminal einschalten. Ohne diese Berechtigung kann das
  Programm keine Tasten an MyWhoosh schicken. Danach das Terminal einmal neu
  starten.

## Benutzung

1. Programm starten und eine Taste am Click drücken, damit er aufwacht.
2. Im Terminal erscheint `✅ Zwift Click v2 (rechts) verbunden.`
3. MyWhoosh öffnen, Fahrt starten und **MyWhoosh im Vordergrund lassen**. Die
   Tasten gehen immer an das Fenster, das gerade aktiv ist.
4. Mit **+** und **B** schalten. Beenden mit `Ctrl+C` im Terminal.

Wenn die Verbindung abbricht, verbindet sich das Programm automatisch neu.

## Andere Tasten

```bash
./start.command --up-key up --down-key down   # Pfeiltasten statt I/K
```

## Fehlersuche

- **Click wird nicht gefunden:** Taste drücken, damit er aufwacht (die LED blinkt).
  Prüfen, dass er nicht gerade mit Zwift, MyWhoosh oder dem Handy verbunden ist.
- **Verbunden, aber es passiert nichts:** Mit `--debug` starten und Tasten drücken.
  - Erscheinen Zeilen wie `Taste + -> up`, fehlt meist die Berechtigung
    *Bedienungshilfen*, oder MyWhoosh ist nicht im Vordergrund.
  - Erscheinen gar keine Zeilen, die Ausgabe hierher kopieren und mir schicken.
- **„Zwift-Dienst nicht gefunden“:** Firmware des Click in der Zwift-Companion-App
  aktualisieren.

## Tests

```bash
python3 -m unittest test_click_bridge
```
