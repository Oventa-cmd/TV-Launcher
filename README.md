# TV-Launcher

Schlichter Startbildschirm für Fire TV / Android TV (Kotlin, Jetpack Compose).

- Große App-Kacheln mit weicher Fokus-Animation, Uhr, Hintergründe
- Einstellungen über die **Menü-Taste** (≡) oder das Zahnrad: Hintergrund, Apps ein/aus, Reihenfolge (◀ ▶), Eingänge
- Standard-Apps: SmartTube, Netflix, Twitch, Jellyfin, ProtonVPN, Kodi (YouTube versteckt, nicht gelöscht)
- Home-Taste: optionaler Bedienungshilfe-Dienst `HomeRedirectService` öffnet diesen Launcher, sobald die Amazon-Startseite erscheint. Er liest keine Bildschirminhalte.

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
