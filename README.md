# Web Launcher (Android nativo)

Launcher di web app in **Kotlin / Android nativo**.

## Funzioni
- Griglia app con categorie e preferiti
- Ricerca testo + **Cerca sul web**
- **Voce nativa** (`RecognizerIntent` di Android, non WebView)
- Tieni premuto su un\u2019app \u2192 Preferiti / Modifica / Elimina
- Aggiungi app, backup JSON, reset
- Apertura link in Chrome Custom Tabs

## Voce
Usa il riconoscimento vocale di sistema (Google).
Richiede permesso **Microfono** e Google app / speech services.

## Build
Push su `main` \u2192 GitHub Actions genera l\u2019APK (`web-launcher-native-apk`).

```bash
./gradlew assembleDebug
```
