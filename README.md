# Web Launcher v2

Launcher intelligente di web app (PWA + Capacitor) con ricerca vocale.

## Novità rispetto alla v1

### Grafica
- Design moderno con glassmorphism e animazioni fluide
- Tema chiaro / scuro (rispetta anche `prefers-color-scheme`)
- Card migliorate, long-press per azioni su mobile
- Bottom navigation
- Toast al posto degli `alert`
- Layout responsive ottimizzato

### Funzioni
- **Ricerca vocale** (microfono) — Web Speech API in italiano
- Preferiti (pin) con sezione dedicata
- Categorie personalizzabili
- Esporta / importa backup JSON
- Reset alle app predefinite
- Ricerca in tempo reale (nome, URL, categoria)
- Omnibox intelligente (URL o ricerca Google)
- Integrazione Capacitor Browser

## Struttura

```
www/
  index.html      # App completa
  manifest.json
  sw.js
capacitor.config.json
package.json
```

## Uso locale

Apri semplicemente `www/index.html` nel browser, oppure:

```bash
npx serve www
```

## Capacitor (Android)

```bash
npm install
npx cap add android   # solo la prima volta
npx cap sync
npx cap open android
```

## Permessi

- **Microfono**: richiesto solo quando si usa la ricerca vocale.
- Su Android (Capacitor) assicurati di dichiarare il permesso microfono se necessario per il WebView.
