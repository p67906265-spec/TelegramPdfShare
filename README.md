# TelegramPdfShare v0.13

App Android locale per cercare automaticamente 7 PDF in un canale Telegram e inoltrarli a WhatsApp.

## Novità v0.13
- Passaggio da **5 a 7 ricerche PDF** indipendenti.
- La home, lo stato, le notifiche e la ricerca automatica ora lavorano su **7 PDF**.
- Restano invariati filtro globale, orari, intervallo, download automatico e gestione giornaliera.

- I messaggi di controllo non richiedono più un secondo canale: vengono inviati automaticamente a **Messaggi salvati** dell’account Telegram collegato.
- Nelle impostazioni resta solo l’interruttore per attivare/disattivare i messaggi di controllo.
- Inclusa la correzione della diagnostica "Prossimo avvio / Ultima ricerca automatica".

- Ogni PDF trovato viene **scaricato automaticamente** nella cache privata dell'app.
- L'avviso sonoro viene inviato quando il PDF è trovato e il download automatico è completato: quindi è già pronto per l'inoltro.
- La home mostra per ogni casella **TROVATO • SCARICATO** quando il file è pronto.
- Nuovo pulsante principale **Inoltra PDF disponibili (N)**: inoltra insieme tutti i PDF già trovati e scaricati, anche se non sono ancora 7/7.
- Restano i pulsanti **Inoltra** sotto ogni singolo PDF.
- La ricerca continua solo per i PDF mancanti, con intervallo configurabile (predefinito 15 minuti).
- Se tutti i PDF sono stati trovati ma un download non è riuscito, l'app continua a riprovare finché anche i file non sono scaricati.
- Rimossa dalle Impostazioni la voce **Gruppo WhatsApp**, perché il nome non poteva selezionare realmente un gruppo.
- Restano: gruppo Telegram, filtro unico parole da NON trovare, minuti, ora di inizio e 5 filtri PDF.
- La data di oggi resta automatica per tutte le ricerche.

## WhatsApp

Il pulsante di inoltro apre WhatsApp con uno o più PDF già allegati. WhatsApp richiede comunque la scelta/conferma della chat o del gruppo e il tocco finale su **Invia**.

## Compatibilità aggiornamenti

- applicationId invariato: `it.paolo.telegrampdfshare`
- versionCode: `10`
- versionName: `0.12`
- minSdk: 26
- mantiene dati, sessione Telegram e configurazione durante l'aggiornamento, usando la stessa chiave di firma permanente.


## v0.9
- avvio automatico più robusto con watchdog di recupero ogni 15 minuti
- home: prossimo avvio e ultima ricerca automatica
- canale Telegram separato per i messaggi di controllo
- interruttore per attivare/disattivare i messaggi di controllo
- messaggio a ogni ciclo automatico e quando un PDF viene trovato e scaricato


## Novità v0.13
- Ora di fine della ricerca automatica configurabile.
- Nessuna ricerca automatica fuori dalla fascia oraria impostata.
- Il watchdog di recupero lavora solo nella fascia utile e poi dorme fino al giorno successivo.
- Dopo l'inoltro di un PDF la casella viene liberata, ma il PDF resta segnato come gestito per la giornata e non viene cercato di nuovo.
- I file inoltrati vengono rimossi dalla cache con un breve ritardo per permettere a WhatsApp di leggerli.
- Al cambio giorno vengono azzerati stati giornalieri e residui della cache PDF.


## v0.13
I campi PDF vuoti sono opzionali: vengono ignorati. La ricerca parte con almeno un filtro configurato e si ferma quando ha completato tutti i filtri attivi.
