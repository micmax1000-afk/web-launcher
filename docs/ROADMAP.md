# Web Launcher — Scaletta delle idee

Stato: ✅ = fatto. Le priorità sono indicative: P0 = da fare prima,
P1 = consigliate, P2 = utili, P3 = quando c'è tempo. In fondo le idee scartate o sconsigliate.

## P0 — Base tecnica (prima di tutto)
| # | Cosa | Perché |
|---|------|--------|
| 1 | ✅ **Firma fissa dell'APK** (7.2.2, chiave nei secret GitHub) | Oggi ogni APK compilato su GitHub può avere una firma diversa: Android rifiuta l'aggiornamento e obbliga a disinstallare, perdendo i dati. Con una chiave fissa gli aggiornamenti si installano sopra. |
| 2 | ✅ **Aggiornamenti dall'app** (7.3.0) | Pagina "Versioni" su GitHub Releases e avviso nell'app "È disponibile la 7.3: aggiorna". |
| 3 | ✅ **Pull request verso `main`** (PR #1 unita) | Tutto il lavoro è sul branch di sviluppo. |
| 4 | ✅ **Dividere il codice in moduli** (MainActivity da 9.600 a 1.900 righe + 20 file per area) | MainActivity è molto grande: dividerla riduce errori e rende le modifiche più sicure. |
| 5 | ✅ **Test automatici** (25 test, già trovati e corretti 2 errori) di base (promemoria, calcoli, backup) eseguiti su GitHub a ogni modifica. |

## P1 — Consigliate
| # | Area | Idea |
|---|------|------|
| 6 | AI | **Assistente sui tuoi dati**: "quanto ho speso in benzina a ottobre?", "cosa scade questo mese?" |
| 7 | Finanza | **Conti e portafogli** (contanti, conto, carta) con saldo |
| 8 | Finanza | **Entrate** e bilancio del mese |
| 9 | Finanza | **Spese ricorrenti automatiche** (affitto, rate, abbonamenti) |
| 10 | Finanza | **Detrazioni per il 730** con documenti fotografati |
| 11 | Borsa | **Watchlist** (azioni, ETF, indici, cambi, cripto, oro) con grafici |
| 12 | Borsa | **Avvisi di prezzo** e notizie con "perché si è mosso oggi?" |
| 13 | Organizzazione | **Eventi a voce nel calendario** ("metti dentista martedì alle 10") |
| 14 | Organizzazione | **Compleanni** dai contatti con auguri su WhatsApp |
| 15 | Promemoria | **"Rimanda 10 minuti" / "Fatto"** direttamente dalla notifica |
| 16 | Notizie | Argomenti da nascondere, notizie già lette in grigio, salva con un gesto |
| 17 | Velocità | **Avvio della home più rapido** |
| 18 | Sicurezza | **SOS** con posizione ai contatti scelti, **"Sono arrivato"** |
| 19 | Casa | **Raccolta differenziata**: calendario del tuo comune con avviso la sera prima |
| 20 | Accessibilità | **Modalità semplice** (icone grandi, solo l'essenziale) e **testo grande ovunque** |

## P2 — Utili
| # | Area | Idea |
|---|------|------|
| 21 | AI | Riepilogo della settimana (domenica sera) |
| 22 | AI | Testo da una foto (copia o salva come nota) |
| 23 | AI | "Cosa cucino?" dagli ingredienti che hai |
| 24 | AI | Studio: riassunti, domande di ripasso e flashcard da PDF e pagine |
| 25 | Finanza | Budget mensile per categoria con avvisi |
| 26 | Finanza | Obiettivi di risparmio |
| 27 | Finanza | Grafici annuali e confronto con l'anno prima |
| 28 | Finanza | Spese divise con amici, prestiti ("Marco mi deve 20 €") |
| 29 | Finanza | Spese in valuta estera convertite in euro |
| 30 | Finanza | Monitor prezzi di un prodotto |
| 31 | Borsa | Portafoglio con guadagni e perdite; indici nel Buongiorno |
| 32 | Casa e auto | Manutenzione auto, rifornimenti e consumo medio |
| 33 | Casa e auto | Letture dei contatori (luce, gas, acqua) |
| 34 | Casa e auto | Inventario casa (manuali, garanzie, assistenza) |
| 35 | Cibo | Scanner dei prodotti alimentari (Open Food Facts): ingredienti, Nutri-Score, allergeni, scadenze |
| 36 | Viaggi | Stato del treno (ritardo, binario) |
| 37 | Viaggi | Modalità viaggio (biglietti, documenti, meteo, valuta, frasi utili) |
| 38 | Organizzazione | Abitudini con spunta giornaliera e serie |
| 39 | Organizzazione | Liste condivise via WhatsApp |
| 40 | Salute | Scheda medica d'emergenza |
| 41 | Avvisi | Allerte della Protezione civile, terremoti (INGV), scioperi dei trasporti |
| 42 | Home | Contatti preferiti in home (chiama, WhatsApp, messaggio) |
| 43 | Home | Widget "Oggi", "Lista della spesa", "Parcheggio" |
| 44 | Podcast | Download automatico in Wi-Fi e coda "ascolta dopo" |
| 45 | Note | Foto e disegni nelle note, cartelle e colori |
| 46 | Ricerca | Ricerca unica anche dentro libri, PDF, ricette e trascrizioni |
| 47 | Backup | Verifica del backup e ripristino selettivo (solo note, solo spese…) |
| 48 | PDF | Firma e compilazione dei moduli PDF, unione di più PDF |

## P3 — Quando c'è tempo
| # | Area | Idea |
|---|------|------|
| 49 | Benessere | Modalità concentrazione (blocca i social per un'ora) |
| 50 | Famiglia | Profili di famiglia (figli, genitori) per scadenze, salute e documenti |
| 51 | Famiglia | Idee regalo per persona |
| 52 | Casa | Piante e animali (innaffiare, vaccini) |
| 53 | Tempo libero | Diario di libri, film e serie |
| 54 | Tempo libero | Ripasso di una lingua con le parole salvate |
| 55 | Ambiente | Qualità dell'aria e pollini |
| 56 | Lavoro | Turni e ore lavorate |
| 57 | Privacy | Icone delle app scaricate dai siti invece che da Google |
| 58 | Home | Più colori e temi, home su più pagine, gesti (scorri per cercare) |
| 59 | Tablet | Impaginazione per tablet e schermi grandi |
| 60 | Accessibilità | Compatibilità completa con TalkBack |
| 61 | Lingua | App anche in inglese |

## Fatto nella 7.4 (idee 62–77)
| # | Cosa | Note |
|---|------|------|
| 62 | ✅ Novità dopo l'aggiornamento | Finestra al primo avvio della nuova versione |
| 63 | ✅ Backup automatico prima di aggiornare | Sul file del backup notturno, o nella cartella Download |
| 64 | ✅ Canale «versioni di prova» | Impostazioni → Aggiornamenti; le prove arrivano dai rami di sviluppo |
| 65 | ✅ Segnala un problema | Con versione, telefono e ultimo errore; apre GitHub o condivide |
| 66 | ✅ Prova su telefono virtuale | A ogni compilazione: schermate + 600 tocchi casuali; pubblica solo se non ci sono crash |
| 67 | ✅ APK più leggero | Da ~12,8 a ~6,5 MB (R8 + solo italiano e inglese) |
| 68 | ✅ Rifiuto automatico dei banner cookie | OneTrust, Didomi, iubenda, Quantcast, Cookiebot… e banner generici |
| 69 | ✅ Report privacy | Tracciatori bloccati oggi/7 giorni/totale, per sito e per tracciatore |
| 70 | ✅ Siti da dimenticare | Cookie e dati cancellati alla chiusura della scheda o dell'app |
| 71 | ✅ Pagine complete offline | Con immagini, da menu ⋮; si aprono senza rete da «Leggi dopo» |
| 72 | ✅ Leggi ad alta voce | C'era già; aggiunta la velocità di lettura |
| 73 | ✅ Bollette in PDF | Condividi il PDF → «È una bolletta»: importo, scadenza, promemoria |
| 74 | ✅ Scontrino da foto | C'era già (Spese → scontrino) |
| 75 | ✅ Scadenze dei documenti da foto | C'era già (Scadenze → Fotografa il documento) |
| 76 | ✅ Notiziario audio | Notizie → «Il punto» → 🎧 Ascolta |
| 77 | ✅ Menu della settimana | Ricette → 📅 Menu: una lista della spesa unica, ingredienti uniti |

## Viaggi
| # | Cosa | Note |
|---|------|------|
| 83 | ✅ Cerca voli e hotel (Strumenti → ✈️ Viaggi) | Una pagina con partenza, arrivo, date e persone che apre già compilati Google Voli, Skyscanner, Booking, Airbnb, Trenitalia e Italo |
| 84 | ❌ Avviso prezzo dei voli (non richiesto) | Usare gli avvisi di Google Voli (gratuiti); un controllo prezzi nostro richiederebbe API a pagamento |
| 85 | ❌ Cartella del viaggio (non richiesta) | Biglietti, prenotazioni, indirizzi e documenti di un viaggio insieme, anche offline |

## Scartate o sconsigliate
| Idea | Motivo |
|------|--------|
| Traduttore integrato (DeepL, Google…) | Deciso di lasciar perdere |
| Risparmio dati | Escluso: rompe mappe e siti con molte immagini |
| Guida TV, risultati sportivi, prezzi carburanti | Non interessano |
| Codici di verifica 2FA (Authenticator) | Sconsigliato: perdere il telefono senza backup blocca gli account |
| Download dei video di YouTube | Viola i termini di YouTube e il diritto d'autore |
| Notifiche immediate di social ed email | Non possibili dal browser interno di Android |
| Sostituire WhatsApp, app bancarie, PosteID, IO | Richiedono le app ufficiali |
| Comodità (78–82): promemoria per luogo, avviso pioggia, timer, widget Oggi, condividi parcheggio | Non interessano per ora |
