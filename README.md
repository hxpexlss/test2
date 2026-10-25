# ClipDL

Android-App, die einen Ausschnitt (Start–Ende) eines YouTube-Videos herunterlädt und als MP3 speichert.
Basiert auf [yt-dlp](https://github.com/yt-dlp/yt-dlp) und ffmpeg über
[youtubedl-android](https://github.com/JunkFood02/youtubedl-android).

## Benutzung

1. Link einfügen – oder in der YouTube-App auf **Teilen → ClipDL** tippen.
2. Start und Ende eintragen (`75`, `1:15` oder `0:01:15`). Leer = ganzes Video bzw. bis Anfang/Ende.
3. Qualität wählen und **Als MP3 herunterladen**.
4. Die Datei landet in `Musik/ClipDL`.

Wenn Downloads plötzlich fehlschlagen, zuerst **yt-dlp aktualisieren** (unten rechts) – YouTube ändert regelmäßig etwas.

## APK bauen

Jeder Push baut per GitHub Actions eine APK und veröffentlicht sie unter **Releases** (`build-N`).
Lokal: `./gradlew assembleRelease` (benötigt Android SDK 35) → `app/build/outputs/apk/release/app-release.apk`.

Hinweis: Bitte nur Inhalte herunterladen, für die du die Rechte hast bzw. die Nutzung erlaubt ist.
