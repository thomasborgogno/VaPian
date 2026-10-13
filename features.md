# Specifiche Funzionali e Architettura: App Autovelox


## 1. Introduzione e Obiettivi del Progetto
Il progetto mira allo sviluppo di un'applicazione android integrata con l'ecosistema **Android Auto**, finalizzata alla mappatura, segnalazione e avviso in tempo reale delle postazioni di autovelox sul territorio italiano. 

L'applicazione fa del crowdsourcing e della filosofia open-source i suoi pilastri fondamentali: integra nativamente i dati geografici di **OpenStreetMap (OSM)** tramite query ottimizzate **Overpass Turbo**, offre un database centralizzato su **Firebase** e permette agli utenti abilitati di contribuire direttamente alla mappa globale ripubblicando le informazioni verificate.

---

## 2. Funzionalità dell'Applicazione Smartphone & PC

### 2.1 Home Page (Schermata Principale)

* **Mappa Interattiva (Google Maps SDK):** Visualizzazione cartografica fluida con marker personalizzati e differenziati cromaticamente in base alla sorgente del dato:
  * *Marker Verde:* Autovelox ufficiale verificato proveniente da OpenStreetMap.
  * *Marker Blu:* Segnalazione utente approvata (`status: 'approved'`).
  * *Marker Arancione/Giallo:* Segnalazione utente in attesa di verifica (`status: 'pending'`).
* **Inserimento ed Eliminazione Punti:**
  * **Inserimento:** Tramite pressione prolungata sulla mappa o pulsante "+" galleggiante (Floating Action Button), l'utente può posizionare il marker sulla mappa, impostarne la direzione sulla mappa, inserire il limite di velocità (selezionabile da preset standard es. 50, 70, 90, 110, 130).
  * **Eliminazione:** Un utente può selezionare un marker inserito da lui e rimuoverlo. L'eliminazione ha effetto immediato sul database locale e cloud.
* **Gestione Blacklist ("Ignora Velox di OSM"):** Se un autovelox importato da OpenStreetMap non è più presente nella realtà, l'utente può cliccare sul marker e selezionare "Ignora". Questo contrassegna l'elemento come nascosto esclusivamente per il profilo dell'utente, rimuovendolo dalla visualizzazione e disattivando gli avvisi acustici.
* **Filtro Mappa:** Un pulsante nella barra superiore, accanto all'icona del profilo, apre un pannello di filtro per i marker visualizzati:
  * *Per limite di velocità:* selezione multipla tra i preset standard; un'opzione dedicata (disattiva di default) permette di includere anche gli autovelox di cui non è noto il limite.
  * *Solo velox ignorati:* quando attiva, sostituisce il filtro per limite mostrando esclusivamente gli elementi della blacklist personale (in grigio); toccarne uno apre il bottom sheet di dettaglio da cui è possibile ripristinarlo (rimuoverlo dalla blacklist).
  * L'icona del pulsante segnala visivamente se un filtro è attivo; dal pannello è sempre disponibile un'azione "Cancella filtro" per tornare alla visualizzazione senza restrizioni.
* **Banner di Conferma Android Auto:** All'avvio dell'app sullo smartphone, un banner persistente in alto segnala se sono presenti elementi registrati rapidamente durante la guida tramite Android Auto. Il banner funge da scorciatoia per validare la posizione e raffinare i dati (es. correzione del limite stimato) per promuovere lo stato da `pending` a `approved`.

### 2.2 Impostazioni dell'Applicazione
* **Sincronizzazione OpenStreetMap (OSM):**
  * Pulsante dedicato al download manuale dei nodi `highway=speed_camera` e `enforcement=maxspeed` dall'API di Overpass Turbo.
  * *Smart Reminder:* Se l'ultima sincronizzazione locale/cloud risale a più di 15 giorni prima, nella Home Page viene mostrato un banner discreto: *"I dati degli autovelox potrebbero non essere aggiornati. Sincronizza ora con OpenStreetMap"*.
* **Configurazione Distanza di Preavviso:**
  * *Opzione Fissa:* Scelta utente tramite slider (Default: 300 metri, range 100m - 1000m).
  * *Opzione Dinamica:* Calcolo algoritmico in tempo reale basato sullo scostamento tra la velocità istantanea ($V_{ist}$) del veicolo e il limite del velox ($V_{lim}$). Più il veicolo procede velocemente o oltre il limite, più lo spazio di frenata teorico aumenta, anticipando la distanza di attivazione dell'allarme.
* **Filtro Tolleranza Intelligente (Attivo di Default):**
  * Per prevenire l'inquinamento acustico nell'abitacolo, l'app **non emette segnali audio** se la velocità del veicolo è inferiore di almeno il 10% rispetto al limite consentito (es. velocità $\le$ 45 km/h su limite di 50 km/h).
  * *Algoritmo di override:* Se durante l'avvicinamento al punto ($d < d_{preavviso}$) il veicolo accelera superando la soglia di tolleranza (velocità $> 45$ km/h), l'avviso sonoro si attiva istantaneamente.

---

## 3. Estensione ed Interfaccia Android Auto

L'applicazione per auto estende il modulo di navigazione nel pieno rispetto delle linee guida di sicurezza di Google (*Android for Cars App Library*), riducendo al minimo il carico cognitivo del guidatore.

### 3.1 Interfaccia di Guida (Driving Mode)
* Proiezione sul display dell'infotainment di una mappa semplificata ad alto contrasto.
* Tracciamento continuo della posizione GPS, calcolo dell'angolo di direzione (heading) e match vettoriale con la variazione della traiettoria per intercettare esclusivamente i velox posizionati nel proprio senso di marcia.

### 3.2 Logica di Avviso Visivo e Audio
* **Interfaccia Grafica (UI):** Al raggiungimento della distanza di soglia impostata, compare sul display una scheda laterale (card) ben visibile con lo sfondo associato al livello di rischio (Giallo se nei limiti, Rosso se in eccesso di velocità), riportante l'icona del radar, il limite di velocità e i metri mancanti.
* **Canale Audio:** Sfruttando il focus audio del sistema operativo di bordo, l'applicazione attenua temporaneamente la musica/radio e riproduce:
  1. Un segnale acustico (Chime) intermittente a frequenza variabile in base alla distanza.
  2. Un messaggio Text-to-Speech (TTS) chiaro, es: *"Attenzione: autovelox fisso a trecento metri. Limite novanta chilometri orari"*.

### 3.3 Azioni Rapide a Grande Pulsante
L'interfaccia di guida mostra due macro-pulsanti touch per l'interazione rapida senza distrazioni:
1. **Pulsante "Ignora Velox" (Solo elementi OSM):** Permette di silenziare immediatamente l'allarme per il velox corrente se l'utente nota che l'apparato è stato rimosso o è inattivo. L'azione inserisce l'ID in una coda temporanea sul telefono. Al termine della guida, l'app smartphone chiederà conferma per inserire permanentemente il velox nella blacklist dell'utente.
2. **Pulsante "Segnala Velox" (Crowdsourcing Rapido):** Consente di registrare una postazione non mappata sul momento. Al tap sul display dell'auto, l'app cattura istantaneamente i seguenti parametri in background:
   * **Posizione esatta:** Coordinate GPS nel millisecondo del tocco.
   * **Direzione:** Vettore di marcia attuale espresso in gradi ($0^{\circ} - 360^{\circ}$).
   * **Stima Limite di Velocità:** Algoritmo a cascata (fallback). L'applicazione interroga i metadati stradali della mappa per estrarre il limite di velocità legale del segmento; se non disponibile, legge la velocità istantanea dell'auto al momento del clic e la arrotonda per eccesso allo scaglione autostradale o urbano più vicino (es. velocità a 104 km/h $\rightarrow$ stima limite a 110 km/h).
   * **Salvataggio:** Il record viene salvato localmente e su Firebase con `status: 'pending'` e `source: 'user'`.

---

## 4. Integrazione e Contribuzione a OpenStreetMap (OSM)

Per evitare che l'applicazione rimanga un silos chiuso di dati privati, viene implementato un modulo di retroazione verso la community mondiale di OpenStreetMap.

* **Autenticazione standard OAuth 2.0:** All'interno delle impostazioni dell'app smartphone, l'utente può autenticarsi con le proprie credenziali OpenStreetMap. Il token di acesso cifrato viene conservato nel portachiavi sicuro del dispositivo (*EncryptedSharedPreferences* su Android / *Keychain* su iOS).
* **Pubblicazione dei Dati Modificati:** Quando l'utente esegue la revisione di un record contrassegnato come `pending` (es. una segnalazione catturata da Android Auto) e lo approva promuovendolo a `approved`, se il login OSM è attivo, l'interfaccia propone un selettore: *"Condividi su OpenStreetMap per aiutare gli altri guidatori"*.
* **Eliminazione autovelox OSM:** se è stato fatto il login OAuth OSM e l'utente ignora un velox, l'interfaccia propone un selettore: *"Elimina autovelox da OpenStreetMap per aiutare gli altri guitatori"*.
* **Scrittura via API REST di OSM:** Previa autorizzazione, l'app apre un canale di modifica ufficiale (*Changeset*) a nome dell'utente sul server OSM ed effettua una chiamata per inserire un nuovo elemento di tipo `node` popolato con i tag geomap standardizzati:
  ```ini
  highway=speed_camera
  maxspeed=[valore_confermato]
  direction=[gradi_o_direzione_cardinale]

  ### 5.1 Schema del Database (Tabella / Collezione `speed_cameras`)


## Struttura dati
La struttura dati per Firebase Firestore

| Nome Campo | Tipo Dati | Vincoli / Indicizzazione | Descrizione |
| --- | --- | --- | --- |
| `id` | String | Chiave Primaria (Unique) | ID univoco generato da Firebase o l'ID numerico nativo del nodo OpenStreetMap. |
| `lat` | Double | Indicizzato (Geohash) | Coordinata di Latitudine in formato decimale (WGS84). |
| `lng` | Double | Indicizzato (Geohash) | Coordinata di Longitudine in formato decimale (WGS84). |
| `limit` | Integer | Non Null | Limite di velocità espresso in km/h (es. 30, 50, 70, 90, 110, 130). |
| `direction` | Integer | Opzionale | Direzione di puntamento dell'autovelox espressa in gradi. |
| `status` | String | Non Null | Stato del record. Valori ammessi: `'approved'` (confermato e attivo per la navigazione), `'pending'` (segnalato da verificare). |
| `source` | String | Non Null | Origine del dato. Valori ammessi: `'user'` (inserito privatamente dall'ecosistema dell'app), `'openstreetmap'` (importato da OSM). |

*Nota sulla Blacklist:* Per tracciare i velox di OpenStreetMap ignorati dall'utente, viene istituita una tabella locale separata denominata `ignored_cameras` contenente esclusivamente la colonna `camera_id` (chiave esterna collegata a `speed_cameras.id`). Nelle query di caricamento della mappa e dei radar attivi, viene eseguita una clausola di esclusione (*LEFT JOIN / NOT IN*).

### 5.2 Logica di Sincronizzazione e Risoluzione Conflitti (Deduplicazione)

Al momento del clic sul pulsante "Sincronizza Velox da OSM", l'applicazione esegue un flusso strutturato volto a proteggere l'integrità dei dati ed evitare la duplicazione dei marker sulla mappa:

1. **Estrazione e Allineamento Cloud:** L'applicazione scarica i dati da Overpass Turbo e scrive i nuovi elementi su Firebase Firestore sotto l'etichetta `source: 'openstreetmap'`. Gli elementi già presenti su Firebase con lo stesso ID OpenStreetMap vengono aggiornati se modificati.
2. **Analisi Prossimità Geometrica:** Il sistema scansiona le segnalazioni con `source: 'user'` inserite dagli utenti e le confronta spazialmente con i nuovi arrivi contrassegnati `source: 'openstreetmap'`. Se la distanza geodetica (calcolata con formula di Haversine) tra un punto utente e un punto OSM è inferiore a una soglia critica definita (es. $\Delta \le 25 \text{ metri}$), viene rilevata una **sovrapposizione di record (Conflitto)**.
3. **Risoluzione Guidata dal Software:** L'applicazione presenta una notifica o un pannello di risoluzione conflitti all'utente all'interno della sezione di riepilogo dello smartphone, offrendo due opzioni di scelta:
* **Opzione A - Assorbi in OpenStreetMap (Scelta Consigliata):** L'utente riconosce che la propria segnalazione corrisponde al punto ufficiale censito su OSM. Il sistema elimina definitivamente il record privato dell'utente (`source: 'user'`) dal database Firebase e locale. Da quel momento in poi, l'app farà affidamento sul marker globale di OpenStreetMap.
* **Opzione B - Mantieni Separati / Ignora OSM:** L'utente ritiene che la propria segnalazione abbia caratteristiche diverse o specifiche migliori rispetto al punto importato da OSM. Il sistema mantiene intatto il record utente e, contemporaneamente, inserisce l'ID del corrispondente punto OpenStreetMap all'interno della blacklist locale. In questo modo si evita la visualizzazione ridondante di due marker sovrapposti sulla mappa durante la guida.
