# TV-Launcher

Schlichter Startbildschirm für Fire TV / Google TV / Android TV (Kotlin, Jetpack Compose).

- Große App-Kacheln mit weicher Fokus-Animation, Uhr, Hintergründe
- Begrüßung nach Tageszeit („Guten Abend, Arne – Was möchtest du schauen?“) mit Lichtschimmer
- Intro-Animation (Kacheln fahren gestaffelt herein), langsam bewegter Hintergrund, Druck-Effekt beim Öffnen
- Dezente Klick-Sounds (in der App erzeugt, keine fremden Audiodateien)
- Alles einzeln abschaltbar unter Einstellungen → „Begrüßung & Effekte“
- Glasoptik (Dock, Uhr, Eingänge, Einstellungen); Kacheln passen sich der Breite an, nichts wird abgeschnitten
- Eingänge sortiert: HDMI 1 links … HDMI 4 rechts
- Hauptmenü-Taste: Guide öffnet den Startbildschirm; andere Taste unter Einstellungen → Fernbedienung anlernbar (braucht den Home-Dienst)
- Einstellungen über die **Menü-Taste** (≡) oder das Zahnrad: Hintergrund, Apps ein/aus, Reihenfolge (◀ ▶), Eingänge
- Standard-Apps: SmartTube, Netflix, Twitch, Jellyfin, ProtonVPN, Kodi (YouTube versteckt, nicht gelöscht)
- Home-Taste: optionaler Bedienungshilfe-Dienst `HomeRedirectService` öffnet diesen Launcher, sobald die Original-Startseite erscheint (Fire TV: Amazon, Google TV: `launcherx`). Er liest keine Bildschirminhalte.

- **StreamTV-Anbindung:** Reiter „Weiterschauen in StreamTV“ in der Watchlist-Zeile (angefangene Folgen mit Fortschritt, OK = direkt weiterschauen); in den Detailansichten „▶ In StreamTV weiterschauen / abspielen“ (Deep Link `streamtv://…`)

Jeder Push auf `main` baut per GitHub Actions eine APK und veröffentlicht sie als Release.

## Home-Taste einschalten (selbst am PC ausführen)

```
adb shell settings put secure enabled_accessibility_services de.oventa.tvlauncher/de.oventa.tvlauncher.HomeRedirectService
adb shell settings put secure accessibility_enabled 1
```

Rückgängig:

```
adb shell settings delete secure enabled_accessibility_services
adb shell settings put secure accessibility_enabled 0
```

## Google TV: Original-Startseite abschalten

Auf Google TV (z. B. TCL) schaltet der Launcher beim ersten Start die Google-TV-Startseite ab (`launcherx` + `setupwraith`), damit Home immer diesen Launcher öffnet. Dafür verbindet er sich per ADB mit dem eigenen Gerät (127.0.0.1:5555). Voraussetzungen: USB-Debugging ist an, und die Abfrage am TV wurde einmal mit „Immer zulassen“ bestätigt.

Umschalten: Einstellungen → System → „Google-TV-Startseite erreichbar“.
Notfall vom PC: `adb shell pm enable com.google.android.apps.tv.launcherx` und `adb shell pm enable com.google.android.tungsten.setupwraith`.
