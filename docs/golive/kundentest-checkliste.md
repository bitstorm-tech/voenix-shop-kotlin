# Go-Live Test-Checkliste aus Kundensicht – Voenix Shop

**Stand:** 2026-11-27  
Ergänzung zum [Go-Live-Fahrplan](README.md) mit allen Kundenpfaden, Kauf- und Zahlungskombinationen sowie Fehler- und Grenzfällen, die vor dem Go-Live (im Staging/Testmodus und abschließend mit echten Live-Testbestellungen) durchgeprüft werden sollten.

> [!TIP]
> **Empfohlene Test-Reihenfolge:**
> 1. **Staging / Mollie-Testmodus:** Alle Fehlerpfade, Gutschein-Ablehnungen, Zahlungsfehler (`FAILED` / `CANCELED` / `EXPIRED`), Account-Sperren und Randfälle gefahrlos durchtesten.
> 2. **Produktion (vor Traffic/Werbung):** Die kompakte **Live-Abnahme-Matrix** (Abschnitt 11) mit echtem Geld, echten E-Mail-Postfächern und echten Smartphones (iOS Safari + Android Chrome) durchführen.

---

## 1. Einstieg, Navigation, Katalog & Darstellung

### Startseite, Header & Footer
- [ ] **Startseite (`/`)**: Alle Bilder, Schriften, Buttons und Links laden fehlerfrei (keine 404-Assets, keine Platzhalter, keine Fake-Bewertungen oder nicht existierende Produkte wie „Poster“/„Als Karte“).
- [ ] **Navigation (Desktop & Mobile)**: Mega-Menü-Kategorien und Unterkategorien öffnen sauber; Klick auf das Logo führt zurück zur Startseite; Mobile-Menü lässt sich öffnen, bedienen und schließen.
- [ ] **Warenkorb- & Magic-Coins-Badge im Header**: Zeigen initial `0` Artikel bzw. `10` Magic Coins an und aktualisieren sich nach Aktionen ohne manuelles Neuladen.
- [ ] **Sprache wechseln (`DE` ↔ `EN`)**: Auf allen Hauptseiten testen – keine rohen i18n-Keys sichtbar, Preise/Daten korrekt formatiert, Rechtstexte & Footer passen zur gewählten Sprache.
- [ ] **Dark Mode ↔ Light Mode**: Kontraste, Lesbarkeit, Logos, Zahlungs-Badges und Editor-Steuerelemente in beiden Modi prüfen.
- [ ] **Unbekannte URLs & alte Weiterleitungen**:
  - [ ] Zufällige URL (z. B. `/gibt-es-nicht`) zeigt saubere 404-Seite mit Rückweg in den Shop.
  - [ ] `http://` und `www.voenix.shop` leiten kanonisch per 301 auf `https://voenix.shop` weiter.
  - [ ] Legacy-Links (z. B. `/impressum`, `/pdf/AGBs.pdf`) funktionieren bzw. leiten sauber weiter.
  - [ ] `/royal-dog` ist (falls zum Launch ausgeblendet) nirgends mehr verlinkt.

### Produktübersicht (`/products`)
- [ ] **Kategorie- & Unterkategorie-Filter**: Artikelanzahlen stimmen; Wechsel zwischen „Alle“ und einzelnen Kategorien aktualisiert URL-Parameter und Grid sauber.
- [ ] **Typ-Filter (`?type=MUG`, `?type=SPOD`) & ungültige Query-Parameter** (z. B. `?category=999999&type=INVALID`): Shop stürzt nicht ab, ungültige Parameter werden bereinigt.
- [ ] **Variantenwechsel auf Produktkarten**:
  - [ ] Tassen (`MUG`): Außen-/Innenfarben umschalten aktualisiert Bild und Auswahl.
  - [ ] Textilien (`SPOD`): Farbe & Größe umschalten aktualisiert Bild und Auswahl.
- [ ] **Preise & Pflichtangaben**:
  - [ ] Preisangaben zeigen `inkl. MwSt., zzgl. Versand` mit klickbarem Link auf `/shipping`.
  - [ ] Keine unbeabsichtigten Streichpreise/Rabatte zum Launch aktiv.
  - [ ] EU-GPSR-Herstellerangaben sind am Produkt einsehbar (nach Paket 6).
  - [ ] Nicht verfügbare/ausgeblendete Varianten sind nicht bestellbar.

---

## 2. Konfigurator / Wizard (`/wizard`), Upload & KI-Generierung

### Alle Einstiegspfade in den Wizard
- [ ] **Direktstart (`/wizard`)**: Reihenfolge *Stil → Produkt → Foto → Generieren* komplett durchlaufen.
- [ ] **Start über Produktkarte (`/products` → Artikel wählen)**: Produkt & Variante sind vorausgewählt, Wizard startet bei Foto-Upload; Schritt 1 bleibt über Zurück/Step-Indicator änderbar.
- [ ] **Upload-First-Start (`/wizard?start=upload`)**: Reihenfolge beginnt mit Foto-Upload.
- [ ] **Vorausgewählter Stil (`/wizard?promptId=<gültig>`)**: Stil ist direkt gesetzt; ungültige oder deaktivierte `promptId` fällt sauber ohne Fehler auf Schritt 1 zurück.

### Foto-Upload & Zuschnitt (`UploadImageStep`)
- [ ] **Upload-Wege**: Dateiauswahl (Desktop), Drag & Drop, **Einfügen aus der Zwischenablage (Strg+V / Cmd+V)**, Smartphone-Galerie und Smartphone-Kamera.
- [ ] **Gültige Formate**: JPEG, PNG, WebP (auch hochauflösendes Smartphone-Foto testen; auf iPhone prüfen, ob HEIC korrekt konvertiert/abgewiesen wird).
- [ ] **Upload-Fehlerfälle (dürfen keine Magic Coins kosten!)**:
  - [ ] Falsches Dateiformat (z. B. `.pdf`, `.gif`, `.svg`, `.txt`) → verständliche Fehlermeldung.
  - [ ] Datei > 10 MiB → verständliche Fehlermeldung zur Größenbeschränkung.
  - [ ] Leere / beschädigte Bilddatei → saubere Ablehnung.
- [ ] **Zuschnitt-Dialog (`ImageCropDialog`)**: Öffnet sich nach Upload, Zoom & Verschieben funktionieren, Zuschnitt übernehmen, erneut bearbeiten, Bild ersetzen, Bild komplett entfernen.
- [ ] **Datenschutz- & Bildrechte-Hinweis** am Upload sichtbar (mit Links zu `/privacy` und `/terms`).

### Stil-Auswahl, Generierung & Magic Coins
- [ ] **Stil ohne Aufpreis (0,00 €)** auswählen und durchlaufen.
- [ ] **Stil mit Aufpreis (`promptPrice > 0 €`)** auswählen → Aufpreis wird inkl. MwSt. angezeigt und bis in Editor, Warenkorb, Checkout, Bestellung und Bestellmail korrekt berechnet.
- [ ] **„Stil überspringen / Originalfoto direkt nutzen“**: Generierungs-Schritt entfällt, Originalbild öffnet direkt im Editor, **0 Magic Coins** verbraucht.
- [ ] **Rücksprung im Wizard**: Nach Generierung zurückgehen und Stil, Artikel oder Foto ändern → alte generierte Bilder werden sauber verworfen.
- [ ] **KI-Generierung für Tasse (`16:9`)** und für **SPOD-Textil (`1:1`)**:
  - [ ] Ladeanimation, wechselnde Statustexte und Timer laufen sauber.
  - [ ] Generiertes Bild hat das richtige Seitenverhältnis; Lightbox/Zoom öffnet und schließt sauber.
  - [ ] Genau **1 Magic Coin** wird abgezogen (Header-Badge aktualisiert sich).
- [ ] **Mehrere Varianten generieren**: „Weitere Variante generieren“ erzeugt Bild 2, 3 … → Thumbnail-Leiste erscheint, Umschalten zwischen Varianten funktioniert, gewählte Variante wird in den Editor übernommen.
- [ ] **Magic Coins: Gast vs. Konto**:
  - [ ] Neuer Gast startet mit 10 Coins; nach 1 Generierung → 9 Coins.
  - [ ] Login in bestehendes/neues Konto → Konto hat eigenen Coin-Stand (kein Merge).
  - [ ] Nach Logout ist der Gast-Coin-Stand (9 Coins) wieder da.
- [ ] **Magic Coins aufgebraucht (0 Coins / HTTP 402)**:
  - [ ] Generieren zeigt klaren Hinweis auf fehlende Magic Coins (kein generischer Fehler).
  - [ ] `/magic-coins` zeigt aktuellen Stand `0` und den korrekten Hinweistext.
- [ ] **Generierungs-Fehlerfälle**:
  - [ ] Provider-/Netzwerkfehler (502 / Timeout – z. B. im Testsystem simulieren) → verständliche Fehlermeldung + Retry-Button, **abgezogener Magic Coin wird zurückerstattet**.
  - [ ] Doppelklick / 2 parallele Tabs bei genau **1 Rest-Coin** → maximal 1 Generierung läuft durch, Balance wird nie negativ.
  - [ ] Rate-Limit (> 20 Generierungen/Stunde pro IP → HTTP 429) → sinnvolle Meldung statt Crash.

---

## 3. Produkt-Editor (`/editor/:draftId`)

- [ ] **Vorschau Tasse (`MUG`)**: Motiv füllt Druckbereich sauber, Variantenwechsel ändert Produkt/Farbe.
- [ ] **Vorschau SPOD-Textil (`SPOD`)**: Motiv sitzt exakt im Druckrahmen auf dem Mockup der gewählten Farbe; Sonderformen mit Druckmaske (z. B. rund/Herz, falls aktiv) werden korrekt maskiert.
- [ ] **Alle Editor-Werkzeuge (Desktop & Mobile-Touch!)**:
  - [ ] **Produkt/Variante wechseln**: Farbe/Größe im Editor ändern aktualisiert Vorschau und Preis.
  - [ ] **Text-Werkzeug**: Text hinzufügen, alle Schriftarten ausprobieren (prüfen: laden lokal, keine Google-Fonts-Requests!), Farbe, Größe, Positionieren, Drehen, Löschen.
  - [ ] **Zuschnitt / Crop**: Zoomen & Verschieben im Druckrahmen, Clamping verhindert leere Ränder.
  - [ ] **Rahmen (`Frames`)**: Rahmen anwenden, wechseln und wieder entfernen.
  - [ ] **Cliparts / Sticker**: hinzufügen, skalieren, drehen, verschieben, löschen.
  - [ ] **Bildvarianten-Wechsler**: Zwischen mehreren generierten Varianten aus dem Wizard umschalten.
  - [ ] **Zurücksetzen / Reset** einzelner Änderungen testen.
- [ ] **Rechtliche Leiste**: Trotz ausgeblendetem Footer sind `Impressum · Datenschutz · AGB` im Wizard und Editor erreichbar.
- [ ] **Ungültige Editor-URLs**: `/editor` ohne ID oder `/editor/nicht-vorhanden` (oder nach hartem Reload) zeigt saubere Hinweismeldung statt weißer Seite.
- [ ] **„In den Warenkorb“**:
  - [ ] Doppelklick während des Hochladens wird blockiert (nur 1 Position im Warenkorb).
  - [ ] Vorschau im Warenkorb zeigt **alle** Editor-Änderungen (Crop, Rahmen, Text, Clipart) und bei SPOD-Artikeln **keinen** T-Shirt-/Mockup-Hintergrund im Druckmotiv!

---

## 4. Warenkorb (`/cart`) & Gutscheincodes (Promotions)

### Warenkorb-Funktionen & Randfälle
- [ ] **Leerer Warenkorb**: Zeigt Empty-State mit Button „Produkte entdecken“; direkter Aufruf von `/checkout` leitet automatisch auf `/cart` um.
- [ ] **Mengensteuerung**: Erhöhen/Verringern (`1` bis `99`), Grenzwert `99` prüfen (darüber nicht möglich, unter `1` nicht möglich).
- [ ] **Positionen zusammenführen (Merge)**:
  - [ ] Exakt gleiches Produkt + Variante + Stil + Motiv erneut hinzufügen → Menge wird addiert (bei 99 gekappt).
  - [ ] Gleiches Produkt mit anderer Variante, anderem Stil oder anderem Motiv → neue separate Zeile.
- [ ] **Position löschen** und **„Warenkorb leeren“** funktionieren und aktualisieren Header-Badge sofort.
- [ ] **Gast- vs. Kunden-Warenkorb**:
  - [ ] Als Gast Artikel in den Warenkorb legen → Einloggen → Kunden-Warenkorb ist getrennt (eigener Stand) → Ausloggen → Gast-Warenkorb ist unverändert da.
- [ ] **Nicht mehr verfügbarer Artikel im Warenkorb**:
  - [ ] Artikel/Variante im Admin deaktivieren, während er im Warenkorb liegt → Warenkorb zeigt Hinweis auf nicht verfügbaren Artikel, **Checkout-Button ist deaktiviert**, Mengenänderung liefert verständliche Fehlermeldung (`CART_ITEM_UNAVAILABLE`).
- [ ] **Preisänderung während Artikel im Warenkorb liegt**:
  - [ ] Preis im Admin ändern → bestehende Zeile behält zunächst ihren Snapshot-Preis; nach Mengenänderung übernimmt sie den aktuellen Preis.

### Gutscheincodes – Alle Erfolgs- & Fehlerfälle
- [ ] **Prozent-Gutschein** (z. B. `10 %`) anwenden → Rabatt korrekt berechnet & gerundet.
- [ ] **Festbetrags-Gutschein** (z. B. `5,00 €`) anwenden → Rabatt korrekt abgezogen.
- [ ] **100-%-Gutschein** bzw. **Festbetrag größer als Warenkorb + Versand** → Endsumme wird genau **`0,00 €`** (niemals negativ!).
- [ ] **Format-Toleranz**: Code in Klein-/Großbuchstaben und mit Leerzeichen davor/danach (z. B. `  sommer10  `) wird akzeptiert.
- [ ] **Gutschein entfernen** und **durch anderen gültigen Code ersetzen** funktioniert; abgelehnter neuer Code überschreibt einen bereits aktiven Code **nicht**.
- [ ] **Alle 7 Gutschein-Ablehnungsgründe einzeln prüfen**:
  - [ ] Unbekannter / falsch geschriebener Code (`PROMOTION_INVALID_CODE`)
  - [ ] Deaktivierter Gutschein (`PROMOTION_INACTIVE`)
  - [ ] Startdatum in der Zukunft (`PROMOTION_NOT_STARTED`)
  - [ ] Enddatum abgelaufen (`PROMOTION_EXPIRED`)
  - [ ] Gutschein mit Pro-Nutzer-Limit als **Gast** eingegeben (`PROMOTION_LOGIN_REQUIRED` → Hinweis zum Einloggen)
  - [ ] Gesamt-Einlöselimit aufgebraucht (`PROMOTION_TOTAL_EXHAUSTED`)
  - [ ] Persönliches Limit als eingeloggter Kunde bereits ausgeschöpft (`PROMOTION_PER_USER_EXHAUSTED`)

---

## 5. Kauf-Kombinationsmatrix (Checkout `/checkout`)

Jede relevante Kombination aus **Kundentyp × Produktart × Zielland × Adresse × Rabatt** mindestens einmal durchspielen:

### A) Produkt-, Versand- & Preiskombinationen
| # | Produkt-Mix | Zielland | Versandkosten | Gutschein / Stil-Aufpreis | Besonderheit zum Prüfen | Status |
|---|---|---|---|---|---|---|
| K1 | Nur **Tasse (`MUG`)** | DE | Unter Freigrenze (kostenpflichtig) | Kein Gutschein, kostenloser Stil | Telefonnummer **leer lassen** → muss durchgehen! | [ ] |
| K2 | Nur **Tasse (`MUG`)** | DE | Über Freigrenze (**kostenlos**) | Mit **kostenpflichtigem Stil** (`promptPrice > 0`) | Stil-Aufpreis in Position, Summe & Mail korrekt | [ ] |
| K3 | Nur **Textil (`SPOD`)** | DE | Regulär | Eigenes Foto (**ohne Stil**) | Ohne Telefonnummer **blockiert**; mit Telefonnummer klappt Kauf + SPOD-Übergabe | [ ] |
| K4 | **Gemischt: Tasse + SPOD-Textil** | DE | Über Freigrenze | Mit Prozent-Gutschein | Telefonnummer Pflicht; Rabatt auf Gesamtsumme; löst **SFTP + SPOD** aus (später Teillieferung testen!) | [ ] |
| K5 | **Tasse oder Textil** | **EU-Ausland** (z. B. AT, FR, NL – jedes aktivierte Land!) | Länderspezifischer Versand (unter & über Freigrenze, falls vorhanden) | Mit Festbetrag-Gutschein | Länderwechsel live im Checkout passt Versand, Rabatt & Summe sofort korrekt an | [ ] |
| K6 | **Warenwert über Versandfreigrenze + Gutschein** | DE / EU | Bleibt **kostenlos** | Gutschein drückt Endsumme unter Freigrenze | Versandkosten springen **nicht** wieder an (Freigrenze zählt vor Gutschein) | [ ] |
| K7 | **0,00-€-Bestellung** (100-%-Gutschein oder Festbetrag ≥ Warenwert + Versand) | DE | Egal | 100 % / Vollabdeckung | Text/Button wechselt auf „Kostenlos bestellen“; **kein** Mollie-Redirect; direkt auf Bestätigungsseite (`PAID`); Warenkorb-Badge sofort auf `0` | [ ] |
| K8 | **Mehrere Stück & mehrere Zeilen** (z. B. 2× Tasse A + 1× Tasse B + 1× Hoodie) | DE | Kostenlos | Mit Gutschein | Alle Einzelpreise, Mengen & Gesamtsumme stimmen auf den Cent mit Mollie überein | [ ] |

### B) Kundentyp- & Adresskombinationen
- [ ] **Gast-Kauf** (Inkognito-Fenster, kein Account).
- [ ] **Eingeloggter Kunde ohne gespeicherte Adresse** mit Haken **„Adresse merken“** → Adresse ist danach im `/profile` gespeichert und beim nächsten Checkout vorbefüllt.
- [ ] **Eingeloggter Kunde mit gespeicherter Adresse**: Daten im Checkout ändern **ohne** „Adresse merken“ → Profil bleibt unverändert.
- [ ] **Rechnungsadresse = Lieferadresse** (Standard-Checkbox aktiv).
- [ ] **Abweichende Rechnungsadresse** (inkl. Test mit „Adresse merken“):
  - [ ] Rechnungsadresse im Inland.
  - [ ] Rechnungsadresse in einem **Nicht-Versandland** (z. B. `CH` oder `US`, 2-stelliges ISO-Kürzel) → muss erlaubt sein!

### C) Checkout-Validierung, Recht & Race Conditions
- [ ] **Pflichtfelder leer** (Name, Straße, Hausnummer, PLZ, Ort, E-Mail) → Formular blockiert mit verständlicher Meldung.
- [ ] **Ungültige E-Mail** (z. B. `test@`, `kein-at`) → Fehlermeldung.
- [ ] **Ungültiges Rechnungsland** (z. B. `DEU`, `12`, leer bei abweichender Rechnung) → Fehlermeldung.
- [ ] **SPOD im Warenkorb + fehlende Telefonnummer**: Inline-Fehlermeldung am Telefonfeld + Toast; verschwindet sofort nach Eingabe.
- [ ] **AGB-Checkbox nicht angehakt** → Submit blockiert mit Hinweis.
- [ ] **Button-Lösung (§ 312j BGB) & Links**:
  - [ ] Direkt über dem Bestellbutton sind Produktmerkmale, „personalisiert“, Vorschau, Lieferzeit, `inkl. MwSt.`, Widerrufsausschluss und akzeptierte Zahlarten klar erkennbar.
  - [ ] Klick auf `/terms`, `/returns`, `/privacy` öffnet im neuen Tab **ohne** Verlust der eingegebenen Checkout-Daten.
- [ ] **Doppelklick auf „Zahlungspflichtig bestellen“**: Button sperrt sofort (`isSubmitting`/`isRedirecting`), es entsteht genau **1** Bestellung und **1** Mollie-Zahlung.
- [ ] **Gleichzeitigkeits-/Randfälle**:
  - [ ] Artikel im Admin deaktivieren, während Checkout-Seite schon offen ist → Submit liefert `CART_ITEM_UNAVAILABLE` mit verständlicher Meldung.
  - [ ] Letztes Kontingent eines Gutscheins wird in Browser B eingelöst, während Browser A auf Checkout steht → Browser A bekommt `PROMOTION_TOTAL_EXHAUSTED`.
  - [ ] Versandland wird im Admin entfernt, während Checkout offen ist → Feld-Fehlermeldung am Versandland.

---

## 6. Zahlvorgänge, Zahlarten & Zahlungsfehler (Mollie)

### A) Alle aktivierten Zahlarten (Deckungsgleich mit Footer & `/payment`!)
- [ ] **Kreditkarte (Visa / Mastercard)** – inkl. **3D-Secure (3DS2)** Bestätigung auf Desktop & Smartphone.
- [ ] **PayPal** – inkl. Login/App-Wechsel auf Smartphone und sauberem Rücksprung in den Shop.
- [ ] **Apple Pay** – auf echtem **iPhone (Safari)** testen.
- [ ] **Google Pay** – auf echtem **Android (Chrome)** testen.
- [ ] **Klarna** *(falls im Mollie-Live-Profil aktiv)* – inkl. Prüfung, dass `AUTHORIZED` → `PAID` sauber verarbeitet wird.
- [ ] **Alle weiteren aktivierten EU-Zahlarten** (z. B. SEPA, iDEAL, Bancontact, EPS, Wero) – jede aktive Methode mindestens 1× testen und abgleichen, dass keine inaktive Methode im Shop beworben wird.

### B) Alle Zahlungsstatus, Abbrüche & Wiederholungsversuche (Retry)
- [ ] **Z1 – Erfolgreiche Zahlung (`PAID`)**:
  - [ ] Rücksprung auf `/order-confirmation?orderId=<id>` zeigt grünen Erfolgsstatus, Bestellnummer und exakten Endbetrag.
  - [ ] Warenkorb ist leer; Bestellung steht auf `PAID`; Produktion (SFTP/SPOD) wird ausgelöst.
- [ ] **Z2 – Kunde bricht bei Mollie ab (`CANCELED`)**:
  - [ ] Bei Mollie auf „Abbrechen“ klicken → Rücksprung zeigt „Zahlung abgebrochen“ (rotes Icon).
  - [ ] Bestellung bleibt `PENDING`, **keine** Produktion gestartet, eventuelle Gutschein-Reservierung wurde freigegeben.
  - [ ] Button **„Erneut bezahlen“** klicken → neue Mollie-Zahlung startet → erfolgreich abschließen → Bestellung wird `PAID` und Produktion startet.
- [ ] **Z3 – Zahlung schlägt fehl (`FAILED`)** *(Mollie-Testmodus „Failed“ bzw. abgelehnte Testkarte / fehlgeschlagenes 3DS)*:
  - [ ] Rücksprung zeigt „Zahlung fehlgeschlagen“.
  - [ ] Bestellung bleibt `PENDING`, Gutschein-Reservierung freigegeben, keine Produktion.
  - [ ] Retry über **„Erneut bezahlen“** (gerne mit anderer Zahlart!) schließt Bestellung erfolgreich ab.
- [ ] **Z4 – Zahlung läuft ab (`EXPIRED`)** *(Mollie-Testmodus „Expired“ oder Checkout liegen lassen)*:
  - [ ] Anzeige „Zahlung abgelaufen“.
  - [ ] Retry über **„Erneut bezahlen“** erzeugt neue Zahlung und lässt sich erfolgreich bezahlen.
- [ ] **Z5 – Verzögerte Bestätigung (`OPEN` / `PENDING` / `AUTHORIZED`)**:
  - [ ] Rücksprung, bevor Mollie-Status final ist → Seite zeigt Wartezustand („Zahlung wird geprüft…“) und pollt automatisch (3s → 6s → 12s → 24s → 48s).
  - [ ] Nach Ende der automatischen Abfragen erscheint der Hinweis + manueller Button **„Status aktualisieren“**.
  - [ ] **Fehlender Webhook**: Wenn Webhook ausbleibt, aktualisiert der Klick auf „Status aktualisieren“ (bzw. Neuladen von `/order-confirmation`) den Status live bei Mollie auf `PAID`.
- [ ] **Z6 – Browser-Tab während/direkt nach Zahlung geschlossen**:
  - [ ] Bei Mollie bezahlen und Tab sofort schließen (kein Rücksprung auf `/order-confirmation`).
  - [ ] Webhook (bzw. Reconciliation nach ~15 Min.) setzt Bestellung trotzdem auf `PAID` und startet Produktion.
- [ ] **Z7 – Retry-Sonderfälle & Missbrauchsschutz**:
  - [ ] **Noch offene Zahlung**: „Erneut bezahlen“ klicken, während Zahlung 1 noch `OPEN` ist → leitet auf dieselbe Mollie-Checkout-URL weiter (keine doppelte Zahlung!).
  - [ ] **Parallel bereits bezahlt**: Tab 1 bezahlt die Bestellung, in Tab 2 wird „Erneut bezahlen“ geklickt → `ORDER_ALREADY_PAID`, Seite springt sauber auf „Bezahlt“.
  - [ ] **Einmal-Gutschein nach Abbruch abgelaufen/erschöpft**: Bestellung 1 mit Einmal-Code abbrechen → Code im Admin deaktivieren oder durch 2. Kunden einlösen → Retry bei Bestellung 1 wird sauber mit Gutschein-Fehlermeldung abgelehnt (kein Mollie-Redirect, kein Doppel-Rabatt!).
  - [ ] **Doppelklick auf „Erneut bezahlen“**: Button sperrt sofort, nur 1 Zahlungsstart.
- [ ] **Z8 – Mollie-Startfehler / Provider nicht erreichbar (`502 PAYMENT_NOT_STARTED`)** *(im Staging/Testsystem simulieren)*:
  - [ ] **Beim Erst-Checkout**: Fehlermeldung erscheint direkt am Bestellbutton, **Warenkorb bleibt vollständig erhalten**, abgebrochene Order wird automatisch `CANCELLED`, Gutschein-Reservierung wird freigegeben → Kunde kann erneut absenden.
  - [ ] **Beim Retry einer `PENDING`-Bestellung**: Bestellung bleibt `PENDING` (nicht `CANCELLED`), sodass Kunde es später erneut versuchen kann.
- [ ] **Z9 – Ungültige / fremde Bestätigungsseite**:
  - [ ] `/order-confirmation` ohne `orderId` oder mit `?orderId=abc` → klare Fehlermeldung + Link zu `/orders`.
  - [ ] `/order-confirmation?orderId=<ID einer fremden Bestellung>` in anderem Browser/Konto → kein Zugriff (`404`/Fehlerzustand), keine fremden Daten sichtbar.

---

## 7. Nach dem Kauf: Bestellübersicht, Dauerlink & Versand

### Bestellhistorie im Kundenkonto (`/orders`)
- [ ] Liste zeigt alle eigenen Bestellungen (neueste zuerst) mit korrektem Status-Badge (`In Bearbeitung`, `Bezahlt`, `Teilweise versendet`, `Versendet`, `Zahlung fehlgeschlagen/abgebrochen/abgelaufen`, `Storniert`).
- [ ] Aufklappen der Bestelldetails (Desktop-Tabelle & Mobile-Kartenansicht) zeigt alle Positionen, Motiv-Vorschau, Varianten, Mengen, Einzelpreise (inkl. Stil-Aufpreis), Rabatt, Versandkosten & Gesamtbetrag.
- [ ] **„Erneut bestellen“ (Reorder)**:
  - [ ] Fügt bestellte Position (Menge 1, zum aktuellen Katalogpreis) direkt in den Warenkorb ein; Toast-Bestätigung erscheint.
  - [ ] Falls Produkt/Variante deaktiviert → sinnvolle Fehlermeldung.
  - [ ] Falls Druckbild nicht mehr verfügbar → Angebot „Neues Bild hochladen“ öffnet Editor für Artikel & Variante.
- [ ] **„Erneut gestalten“ (Redesign)**: Lädt das ursprüngliche Druckmotiv in den Editor; Änderungen können gespeichert und neu bestellt werden.

### Permanenter Bestelllink (`/order/:token`)
- [ ] Link aus der Bestellbestätigungs-Mail im **Inkognito-Fenster / anderen Browser ohne Login** öffnen (sowohl für Gast- als auch für Konto-Bestellungen testen!).
- [ ] Zeigt Bestellnummer, Datum, Status-Badge, alle Positionen, Beträge und Sendungsverfolgung (Read-Only, keine Konto-Buttons).
- [ ] Manipulierter / zu kurzer / unbekannter Token (z. B. `/order/ungueltig123`) → saubere „Link ungültig“-Seite mit Rückweg zur Startseite.

### Versand & Sendungsverfolgung (SFTP + SPOD)
- [ ] **Einzelversand**: Nach Versandmarkierung (Lieferanten-Portal oder SPOD-Webhook) springt Status in `/orders` und `/order/:token` auf **Versendet (`SHIPPED`)**; Versanddatum, Versanddienstleister (z. B. DHL), Tracking-Nummer und funktionierender Tracking-Link sind sichtbar.
- [ ] **Teillieferung (gemischte Bestellung Tasse + SPOD-Textil)**:
  - [ ] Nach Versand von Paket 1 → Status **Teilweise versendet (`PARTIALLY_SHIPPED`)**, Tracking-Link 1 sichtbar.
  - [ ] Nach Versand von Paket 2 → Status **Versendet (`SHIPPED`)**, beide Pakete & Tracking-Links sichtbar.

---

## 8. E-Mails (Inhalt, Links, Sonderzeichen & Inbox-Zustellung)

### Zustellbarkeit & Darstellung
- [ ] Zustelltest an echte Postfächer bei **Gmail, GMX, Web.de und Outlook.de** (plus ggf. Apple Mail): Alle Mails landen im **Posteingang (Inbox)**, nicht im Spam.
- [ ] Darstellung am **Desktop und Smartphone** (inkl. Dark Mode in Mail-Apps) prüfen – keine abgeschnittenen Tabellen, Buttons klickbar, Plain-Text-Version lesbar.
- [ ] **Sonderzeichen-Test**: Bestellung mit Umlauten & Sonderzeichen im Namen/Adresse (z. B. `Jörg Müller-Lüdenscheid`, `Am Bächle & Co.`) → korrektes Encoding, kein kaputtes HTML.

### Alle 6 Kunden-E-Mail-Typen einzeln prüfen
- [ ] **Bestellbestätigung**:
  - [ ] Kommt zeitnah nach Bestellaufgabe (auch bei noch offener/abgebrochener Zahlung, Text ist zahlungsneutral).
  - [ ] Artikel, Varianten, Mengen, Einzelpreise (inkl. Stil-Aufpreis), Zwischensumme, Versand („Kostenlos“ bei 0 €), Rabattzeile (nur wenn > 0 €), Endsumme `inkl. MwSt.`, Lieferadresse und Lieferzeit stimmen.
  - [ ] Dauerhafter Bestelllink (`/order/:token`) funktioniert.
  - [ ] **AGB & Widerrufshinweis** (Ausschluss bei personalisierter Ware) sind enthalten (§ 312f Abs. 2 BGB).
  - [ ] Vollständiger Anbieter-Footer (JoTo AI GbR, Gesellschafter, Anschrift, E-Mail, Telefonnummer, USt-IdNr.).
- [ ] **Versandbenachrichtigung**:
  - [ ] Kommt je versendetem Paket (bei gemischter Bestellung = 2 Mails).
  - [ ] Enthält Vorname, Versanddienstleister, Tracking-Nummer, funktionierenden Tracking-Button und Bestelllink; nennt **keine** Preise und behauptet bei Teillieferung nicht „komplett versendet“.
- [ ] **Registrierungs-Bestätigung**: Link (`/confirm-email?...`) funktioniert.
- [ ] **Passwort vergessen / Reset**: Link (`/reset-password?...`) funktioniert.
- [ ] **Passwort geändert**: Sicherheits-Benachrichtigung kommt an.
- [ ] **E-Mail-Adresse ändern**: Bestätigungs-Mail an **neue** Adresse (`/confirm-change-email?...`) **und** Informations-Mail an **alte** Adresse kommen beide an.

---

## 9. Kundenkonto, Authentifizierung & Privatsphäre

### Registrierung (`/register`) & E-Mail-Bestätigung (`/confirm-email`)
- [ ] Registrierung mit neuer E-Mail & Passwort (≥ 8 Zeichen) → Erfolgsmeldung, Bestätigungs-Mail kommt an.
- [ ] Datenschutz-Hinweis mit Link zu `/privacy` ist im Registrierungsformular sichtbar.
- [ ] Validierung: ungültige E-Mail, Passwort < 8 Zeichen, fehlende Bestätigung → klare Fehlermeldungen.
- [ ] Bereits bestätigte E-Mail erneut registrieren (auch in anderer Groß-/Kleinschreibung) → verständliche Fehlermeldung (`409`).
- [ ] Noch unbestätigte E-Mail erneut registrieren → sendet neuen Link, vorheriger Link wird ungültig.
- [ ] Bestätigungslink klicken → Konto bestätigt; **denselben Link ein 2. Mal klicken** → saubere „Link ungültig/abgelaufen“-Meldung.
- [ ] „Bestätigungs-Mail erneut senden“ testen (für unbestätigtes Konto kommt Mail; für unbekannte/bereits bestätigte Adresse kein Informationsleck).

### Login (`/login`), Sperre & Logout
- [ ] Login mit **unbestätigter** E-Mail → klare Meldung (403) + Möglichkeit zum erneuten Senden der Bestätigung.
- [ ] Login mit **falschem Passwort** vs. **unbekannter E-Mail** → identische Fehlermeldung.
- [ ] **Account-Lockout**: 15× falsches Passwort → Konto für 10 Minuten gesperrt (`429`) mit klarer Meldung; nach Ablauf (oder Passwort-Reset) Login wieder möglich.
- [ ] Passwort-Anzeigen-Icon (Auge) funktioniert in allen Passwortfeldern.
- [ ] **Logout & Datenschutz auf geteiltem Gerät**:
  - [ ] Nach Logout leiten `/profile` und `/orders` zurück auf `/login`.
  - [ ] **Wichtig:** Bestellungen und hochgeladene Druckbilder des eingeloggten Kontos sind nach dem Logout im selben Browser für einen Gast **nicht** mehr abrufbar!

### Passwort vergessen (`/forgot-password` & `/reset-password`)
- [ ] Reset anfordern für existierende & nicht existierende E-Mail → identische Erfolgsmeldung.
- [ ] Link aus Mail öffnen, neues Passwort setzen (Validierung < 8 Zeichen & abweichende Wiederholung prüfen) → Login mit neuem Passwort klappt, altes Passwort abgewiesen.
- [ ] Reset-Link ein zweites Mal öffnen → „Link ungültig/abgelaufen“.
- [ ] Reset bei einem noch unbestätigten oder gesperrten Konto → Konto wird bestätigt & Sperre aufgehoben.

### Profil (`/profile`)
- [ ] Lieferadresse & optionale separate Rechnungsadresse speichern → nach Seiten-Reload noch vorhanden.
- [ ] **E-Mail-Adresse ändern**:
  - [ ] Falsches aktuelles Passwort → abgelehnt.
  - [ ] Bereits belegte neue E-Mail → abgelehnt (`409`).
  - [ ] Gültige Änderung → Login bleibt bis zum Klick auf den Bestätigungslink auf der **alten** E-Mail; nach Klick funktioniert Login nur noch mit der **neuen** E-Mail.
- [ ] **Passwort ändern**:
  - [ ] Falsches aktuelles Passwort oder ungleiche Wiederholung → abgelehnt.
  - [ ] Gültige Änderung → aktuelle Sitzung bleibt eingeloggt, Benachrichtigungs-Mail geht raus, neuer Login nur noch mit neuem Passwort möglich.

---

## 10. Rechtliche Seiten, Info-Seiten & Technik-Check im Browser

- [ ] **Alle 8 Content-/Rechtsseiten in `DE` und `EN` prüfen** (vollständig, aktuell, keine toten Links):
  - [ ] `/imprint` (Impressum inkl. Gesellschafter, Anschrift, E-Mail, **Telefonnummer**, USt-IdNr., § 36 VSBG)
  - [ ] `/privacy` (iubenda-Datenschutzerklärung lädt sauber in DE & EN; enthält fal.ai, Mollie, Sweego, Sliplane, SPOD, Tassen-Lieferant; keine alten Stripe-/Facebook-Pixel-/GTM-Einträge)
  - [ ] `/terms` (finale AGB in DE; EN zeigt verbindlichen deutschen Text mit Hinweis)
  - [ ] `/returns` (Widerrufsausschluss für personalisierte Ware, § 312g Abs. 2 Nr. 1 BGB; EN zeigt verbindlichen deutschen Text mit Hinweis)
  - [ ] `/shipping` (dynamische Länderliste + echte Versandkosten, Freigrenzen & Lieferzeiten je Land)
  - [ ] `/payment` (exakt die bei Mollie aktiven Zahlarten; deckungsgleich mit Footer-Logos)
  - [ ] `/about`, `/faq` (echte Inhalte in DE & EN, `mailto:write-us@voenix.shop` klickbar)
- [ ] **Browser-DevTools-Check (Netzwerk & Cookies)**:
  - [ ] Keine Fehler in der Browser-Konsole auf allen Hauptseiten.
  - [ ] Keine unerwünschten Drittanbieter-Requests (kein Google Fonts, kein Tracking-Pixel, kein Analytics).
  - [ ] Cookies (`voenix.auth`, `voenix.guest`, `XSRF-TOKEN`) haben auf `https://voenix.shop` das `Secure`-Attribut.

---

## 11. Endgeräte, Browser & finale Live-Abnahme (T0 vor Traffic)

### Geräte & Bedienungs-Stresstest
- [ ] **iPhone (iOS Safari)**: Foto-Upload (Galerie + Kamera), Crop-Dialog, Editor-Gesten (Verschieben/Zoomen ohne unbeabsichtigtes Seiten-Scrollen), Floating-Buttons über dem Home-Indicator (`safe-area-inset-bottom`), Apple Pay & PayPal-Rücksprung.
- [ ] **Android (Chrome)**: Foto-Upload, Editor-Bedienung, Google Pay, PayPal-/Banking-App-Wechsel mit Rückkehr in den Tab.
- [ ] **Desktop**: Chrome, Firefox, Safari/Edge (1366 px, 1920 px und Tablet ~768 px).
- [ ] **Browser-Zurück-Button & Multi-Tab**:
  - [ ] Von Mollie per Browser-Zurück-Button zurück in den Shop navigieren → kein kaputter Zustand.
  - [ ] In Tab 2 Warenkorb leeren/ändern und in Tab 1 Checkout abschicken → saubere Fehlermeldung statt Crash.
- [ ] **Tastatur-Bedienung**: Login, Registrierung und Checkout komplett per `Tab` und `Enter` durchführbar.

### Finale Live-Testbestellungen (mit echtem Live-Mollie-Key & Live-Fulfillment)
- [ ] **Live-Test 1 – Tasse (DE, SFTP, Gast, mit Stil-Aufpreis & Smartphone iOS Safari)**:
  Foto → KI-Generierung → Editor (Text + Rahmen) → Warenkorb → DE-Checkout ohne Telefonnummer → echte Zahlung → Bestellmail in Inbox prüfen → `/order/:token` öffnen → Produktions-PDF auf SFTP & Lieferanten-Mail prüfen → als versendet markieren → Versandmail + Tracking-Link prüfen.
- [ ] **Live-Test 2 – Textil (EU-Ausland, SPOD, `EN`-Oberfläche, Android Chrome)**:
  Länderwechsel im Checkout prüft EU-Versandkosten → Telefonnummer-Pflicht → echte Zahlung → Live-Order in Spreadconnect inkl. Motiv & Druckposition prüfen → Versand-Webhook & Versandmail prüfen.
- [ ] **Live-Test 3 – Gemischte Bestellung (Tasse + Textil) & Zahlungsabbruch + Retry (eingeloggtes Konto)**:
  Registrierung → E-Mail bestätigen → Login → 1× Zahlung bei Mollie abbrechen → über „Erneut bezahlen“ erfolgreich zahlen → beide Fulfillment-Jobs & Teillieferung (`PARTIALLY_SHIPPED` → `SHIPPED`) prüfen.
- [ ] **Live-Test 4 – Kostenlose 0,00-€-Bestellung via 100-%-Gutschein**:
  Bestellung ohne Mollie-Redirect direkt bestätigt, Gutschein als eingelöst gezählt, Produktion ausgelöst.
