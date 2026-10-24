# Zwift Click → MyWhoosh

Mit dem Zwift Click in MyWhoosh schalten. Es gibt zwei Brücken, nimm die, die
gerade passt:

| | **Mac-Brücke** | **Android-App** |
|---|---|---|
| Läuft auf | MacBook (Terminal) | Android-Handy |
| MyWhoosh läuft auf | demselben MacBook | beliebigem Gerät im selben WLAN (Mac, iPad, Apple TV, Android, auch dasselbe Handy) |
| Schaltet über | Tasten **I** / **K** | **MyWhoosh Link** (offizielle Begleit-App-Schnittstelle) |

Belegung, bei beiden gleich:

| Zwift Click (1. Generation) | MyWhoosh |
|---|---|
| **+** | hochschalten |
| **–** | runterschalten |

Der Click kann immer nur mit **einem** Gerät verbunden sein. Verbinde ihn also
nicht zusätzlich direkt in MyWhoosh oder Zwift, und lass nicht beide Brücken
gleichzeitig laufen.

---

## Mac-Brücke

### Einrichtung (einmalig)

1. **Python 3 installieren**, falls noch nicht vorhanden: https://www.python.org/downloads/
2. Diesen Ordner `zwift-click-bridge` auf den Mac holen, zum Beispiel per `git clone`.

### Starten

Doppelklick auf **`start.command`**. Beim ersten Start installiert es selbst,
was es braucht. Falls macOS meldet, dass die Datei nicht geöffnet werden darf:
Rechtsklick → **Öffnen**, oder im Terminal `bash start.command`.

Beim ersten Start fragt macOS nach zwei Berechtigungen für das **Terminal**:

- **Bluetooth**: erlauben.
- **Bedienungshilfen** (Systemeinstellungen → Datenschutz & Sicherheit →
  Bedienungshilfen): Terminal einschalten. Ohne diese Berechtigung kann das
  Programm keine Tasten an MyWhoosh schicken. Danach das Terminal neu starten.

### Benutzung

1. `start.command` starten und eine Taste am Click drücken, damit er aufwacht.
2. Im Terminal erscheint `✅ Zwift Click verbunden.`
3. MyWhoosh öffnen, Fahrt starten und **MyWhoosh im Vordergrund lassen**. Die
   Tasten gehen immer an das Fenster, das gerade aktiv ist.
4. Mit **+** und **–** schalten. Beenden mit `Ctrl+C` im Terminal.

Wenn die Verbindung abbricht, verbindet sich das Programm automatisch neu.

### Weitere Optionen

```bash
./start.command --scan                         # nur anzeigen, welche Clicks in der Nähe sind
./start.command --debug                        # alle Bluetooth-Nachrichten anzeigen
./start.command --up-key up --down-key down    # Pfeiltasten statt I/K
```

---

## Android-App

Das Handy verbindet sich mit dem Click und schickt die Schaltbefehle über das
WLAN an MyWhoosh. Es tut so, als wäre es die offizielle App „MyWhoosh Link“.
Deshalb darf MyWhoosh auf einem beliebigen Gerät laufen, solange es im selben
WLAN ist wie das Handy.

### Installieren

1. Auf dem Handy die neueste APK öffnen: in diesem GitHub-Repo unter
   **Releases** → **Click Bridge Android …** → `ClickBridge.apk`.
2. Herunterladen und öffnen. Android fragt, ob der Browser Apps installieren
   darf („Unbekannte Apps installieren“): einmal erlauben.
3. Bei einer neueren Version vorher die alte App deinstallieren. Jede Version
   wird neu signiert, deshalb klappt ein direktes Update nicht.

### Benutzung

1. **Die App „MyWhoosh Link“ schließen**, falls sie installiert ist. Sie
   belegt sonst dieselbe Verbindung.
2. **Click Bridge** öffnen → **Starten** → Bluetooth und Benachrichtigungen erlauben.
3. Eine Taste am Click drücken. Es erscheint `Click: ✅ Zwift Click verbunden`.
4. MyWhoosh starten und im Verbindungs- bzw. Pairing-Bildschirm nach
   **MyWhoosh Link** suchen. Sobald MyWhoosh verbunden ist, steht in der App
   `MyWhoosh: MyWhoosh verbunden`.
5. Mit **Test: hoch** und **Test: runter** kannst du das Schalten auch ohne Click ausprobieren.

Die App läuft im Hintergrund weiter, auch bei ausgeschaltetem Bildschirm. Man
erkennt das an der Benachrichtigung. Zum Beenden in der App **Stoppen** tippen.

**Wenn MyWhoosh die App nicht findet:**

- Handy und MyWhoosh-Gerät im **selben WLAN**, kein Gäste-WLAN, kein VPN.
- Manchen hilft es, einmal die echte „MyWhoosh Link“-App mit MyWhoosh zu
  verbinden, sie wieder zu schließen und dann Click Bridge zu starten.
- Router-Option „AP-/Client-Isolation“ ausschalten, falls vorhanden.

---

## Fehlersuche (beide)

- **Click wird nicht gefunden:** Taste drücken, damit er aufwacht (die LED
  blinkt). Prüfen, dass er nicht gerade mit Zwift, MyWhoosh oder einem anderen
  Gerät verbunden ist.
- **„Zwift-Dienst nicht gefunden“:** Firmware des Click in der
  Zwift-Companion-App aktualisieren.
- **Mac: verbunden, aber es passiert nichts:** mit `--debug` starten und Tasten
  drücken.
  - Erscheint `Taste + -> up`, fehlt meist die Berechtigung
    *Bedienungshilfen*, oder MyWhoosh ist nicht im Vordergrund.
  - Erscheint gar nichts, die Ausgabe kopieren und mitschicken.

## Zwift Click v2

Beide Brücken funktionieren auch mit dem Click v2 (zwei Controller mit je vier
Tasten). Zwift sperrt dort aber die linke Seite für andere Apps, deshalb wird
nur die **rechte** Seite benutzt: **+** schaltet hoch, **B** schaltet runter.
Die Mac-Brücke kann mit `--both` auch die linke Seite verbinden. Das klappt nur,
wenn der Click vorher in der Zwift-App freigeschaltet wurde.

## Für Entwickler

- Mac-Brücke testen: `python3 -m unittest test_click_bridge`
- Android bauen: `cd android && ./gradlew testDebugUnitTest assembleDebug`.
  Bei jedem Push baut GitHub Actions die APK und veröffentlicht sie als Release.
