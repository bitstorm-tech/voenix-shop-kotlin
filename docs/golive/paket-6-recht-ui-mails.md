# Paket 6: Rechtliche Seiten, Kasse, Bestellmails, PAngV, GPSR & Katalog-UI

**Enthaltene Punkte aus `GOLIVE-TODO.md`:** `A1` (Fallback/EN), `A2`, `A3`, `A4`, `A5`, `A7`, `A8`, `A10`, `A11`, `A13`, `A14`, `A16`, `A18`, `A20`, `A21`, `C1`, `C2`, `C4`  
**Risikoprofil:** Mittel bis Hoch (rechtliche Pflichtangaben im Shop & in Bestellmails; berührt Frontend + Mail-Templates + ggf. Artikel-Schema für GPSR).  
**Empfohlener Zeitpunkt:** **Nach Paket 3** (teilt sich `CheckoutView.vue`, `de.json`/`en.json`) und sobald die juristischen Texte & Produktentscheidungen vorliegen.

---

## Festgezurrte Entscheidungen (Stand 2026-11-25)

1. **Stil-Aufpreis (`C2`):** **Möglich machen** — `promptId` sauber durch Wizard → `EditorDraft` → `ProductEditor` → `addToCart` durchreichen und inkl. MwSt. anzeigen.
2. **Versandländer (`D3`/`A21`):** **Start in mehreren EU-Ländern** mit länderspezifischen Versandkosten aus der Tabelle `countries` (Backend/Admin/Checkout-Grundlage kommt über [`paket-7-versandkosten-countries.md`](paket-7-versandkosten-countries.md)).
3. **Werbeaussagen (`A11`):** **„Made in Germany“** und **„DHL aus Deutschland“** sind korrekt und bleiben erhalten. Entfernt werden nur nicht existierende Produkte („Poster“, „Als Karte“); Zahlarten und DE-/EU-Lieferzeiten werden exakt abgeglichen.
4. **`/royal-dog` (`C4`):** Route & Verlinkungen für den Launch **vorerst ausblenden** (kein Prompt-Blocker).
5. **Footer-Seiten (`C1`):** **Alle** Seiten (`/about`, `/faq`, `/contact`, `/support` sowie `/payment`, `/terms`, `/returns`) erhalten echte Inhalte (DE + EN); keine Footer-Links entfernen.
6. **Aufteilung in 6A und 6B:**
   - **Teil 6A (sofort ohne externe Texte umsetzbar):** Abschnitte **2** (`A1`-Code-Fallback), **3** (`A7`, `A8`, `A18`), **5** (`A10`, `A11`-Streichungen, `A14`, `A20`), **6** (`A13` EU-GPSR) und **7** (`C2`, `C4`).
   - **Teil 6B (sobald Rechtstexte, Footer-Inhalte, Mollie-Zahlarten & Paket 7 vorliegen):** Abschnitte **1** (`A2`, `A3`, `A5`, `A16`, `A21`, `C1`) und **4** (`A4` Bestellbestätigungs-Mail).

**Noch von Joe zuzuliefernde Texte/Angaben für Teil 6B:** Finale **AGB** (DE+EN, `A2`), **Widerrufsausschluss** (DE+EN, `A3`), **Telefonnummer** (`A5`), finale Liste der **aktiven Mollie-Zahlarten** (`A11`/`A16`), englische **iubenda-Policy-ID** (`A1`) sowie redaktionelle Inhalte (DE+EN) für `/about`, `/faq`, `/contact` und `/support` (`C1`).

---

## Aufgaben im Detail

### 1. `A2`, `A3`, `A5`, `A16`, `C1` — Rechtliche Seiten & Footer bereinigen
- **Dateien:**
  - `frontend/src/views/shop/TermsView.vue` (`A2`)
  - `frontend/src/views/shop/ReturnsView.vue` (`A3`)
  - `frontend/src/views/shop/ImprintView.vue:47-70` (`A5`: Telefonnummer + § 36 VSBG-Hinweis ergänzen)
  - `frontend/src/views/shop/PaymentView.vue` (`A16`: real aktive Mollie-Zahlarten eintragen)
  - `frontend/src/views/shop/ShippingView.vue` (`A21`: Lieferzeiten ergänzen, Länderliste aus `/api/countries` statt hardcodiert, „ab 150 € Umsatz“ prüfen)
  - `frontend/src/components/shop/Footer.vue:29-52` (`C1`: alle Footer-Links behalten — Seiten werden in Teil 6B gefüllt; Zahlungs-Logos exakt an aktive Mollie-Methoden anpassen)
  - `frontend/src/views/shop/CheckoutView.vue:463-469` (`A2`: toten WordPress-PDF-Link `https://voenix.shop/pdf/AGBs.pdf` durch `<RouterLink :to="{ name: 'terms' }" target="_blank">` ersetzen)

### 2. `A1` (Code-Teil) — Datenschutzerklärung gegen iubenda-Ausfall absichern
- **Dateien:**
  - `backend/src/modules/legal/index.ts:8-69`
  - `frontend/src/views/shop/PrivacyView.vue:18-45`
- **Umsetzung:**
  - Response-Größe via `readCapped` begrenzen und einen lokalen Fallback (auf Disk gecachte letzte gültige Kopie oder statische Kopie im Repo) ausliefern, falls iubenda beim Cold-Start nicht erreichbar ist (kein 502 auf `/privacy`).
  - Falls englische UI aktiv bleibt: passende EN-Policy-ID oder Hinweis ergänzen.

### 3. `A7`, `A8`, `A16`, `A18` — PAngV, Button-Lösung & DSGVO-Links in Shop & Kasse
- **Dateien:**
  - `frontend/src/components/shop/ProductPrice.vue:64-99`
  - `frontend/src/components/shop/CartSummary.vue:62-65`
  - `frontend/src/views/shop/CheckoutView.vue:370-473`
  - `frontend/src/components/shop/OrderDetails.vue:160-163`
  - `frontend/src/views/auth/RegisterView.vue:85-142`
- **Umsetzung:**
  - **`A7` (PAngV):** `inkl. MwSt., zzgl. Versand` (mit Link auf `/shipping`) an Produktpreisen; `inkl. MwSt.` an Warenkorb-, Kassen- und Bestell-Gesamtsummen.
  - **`A8` (Button-Lösung § 312j Abs. 2 BGB):** Direkt über dem Bestellbutton je Position wesentliche Produktmerkmale (Produktart/Material/Füllmenge, „personalisiert“), Motiv-Vorschau (`item.imageId`), Lieferzeit und `inkl. MwSt.` anzeigen.
  - **`A16` + `A3` + `A18`:** Im Checkout akzeptierte Zahlarten klar nennen, Hinweis auf Ausschluss des Widerrufsrechts für personalisierte Ware (§ 312g Abs. 2 Nr. 1 BGB) mit Link zu `/returns` + Link zu `/terms` und `/privacy` anzeigen; in `RegisterView.vue` ebenfalls Datenschutz-Hinweis mit Link zu `/privacy` ergänzen.

### 4. `A4` — Bestellbestätigungs-Mail als dauerhafter Datenträger (§ 312f Abs. 2 BGB)
- **Dateien:**
  - `backend/src/modules/email/templates/order-confirmation.ts:78-145`
  - `backend/src/modules/email/templates/layout.ts:83,121-123`
- **Umsetzung:**
  - AGB und Widerrufshinweis (Ausschluss bei personalisierter Ware) als Text oder Anhang mitsenden.
  - `inkl. gesetzl. MwSt.` und Lieferzeit in der Bestellzusammenfassung ausweisen.
  - Vollständige Anbieterangaben (JoTo AI GbR, Gesellschafter, Anschrift, E-Mail, Telefonnummer, USt-IdNr.) in den Mail-Footer von `layout.ts` aufnehmen.
  - Mail-Template-Tests aktualisieren.

### 5. `A10`, `A11`, `A14`, `A20` — UWG-Bereinigung, Upload-Hinweise & Wizard-Legal-Leiste
- **Dateien:**
  - `frontend/src/views/shop/HomeView.vue:139-159,230,276,328-370`
  - `frontend/src/components/shop/campaigns/royal-dog/RoyalDogReviews.vue`
  - `frontend/src/i18n/locales/de.json`, `en.json`
  - `frontend/src/components/shop/wizard/steps/UploadImageStep.vue:44-75`
  - `frontend/src/components/shop/editor/ProductDraftUpload.vue:44-70`
  - `frontend/src/layouts/ShopLayout.vue:20,36`
- **Umsetzung:**
  - **`A10`:** Hardcodierte Kundenstimmen auf Startseite & `/royal-dog` entfernen sowie ungenutzte Fake-Stats (`4,9 Sterne`, `50k+ Designs`) aus `de.json`/`en.json` löschen.
  - **`A11`:** Nicht existierende Produkte („Poster“, „Als Karte“) entfernen; „Made in Germany“ und „DHL aus Deutschland“ bleiben erhalten; Zahlarten und DE-/EU-Lieferzeit-Angaben überall konsistent halten.
  - **`A14`:** In **beiden** Upload-Komponenten (`UploadImageStep.vue` und `ProductDraftUpload.vue`) einen kurzen Hinweis zu Bildrechten, abgebildeten Personen und KI-Verarbeitung mit Links zu `/privacy` und `/terms` einbauen.
  - **`A20`:** Auch bei `hideFooter: true` (`/wizard`, `/editor`) eine schmale Legal-Leiste (`Impressum · Datenschutz · AGB`) rendern.

### 6. `A13` — EU-GPSR Herstellerangaben im Artikelmodell & in der Shop-UI
- **Dateien:**
  - `backend/migrations/` *(neue Migration für Herstellerfelder an Artikeln/Lieferanten, je nach gewünschtem Modell)*
  - `backend/src/modules/article/schema.ts`, `storefront.ts`, Admin-Artikel-API & Admin-UI
  - `frontend/src/components/shop/ProductCard.vue`, `SelectArticleStep.vue` (bzw. Artikel-Detail/Info-Dialog)
- **Umsetzung:**
  - Herstellerangaben (Name, Postanschrift, elektronische Kontaktadresse + ggf. Sicherheits-/Warnhinweise) im Artikel- oder Lieferantenmodell speichern, im Admin pflegbar machen und im Storefront am Angebot sichtbar anzeigen (Art. 19 EU-GPSR).

### 7. `C2` & `C4` — Stil-Aufpreis & `/royal-dog`
- **`C2` (`SelectStyleStep.vue:238-243`, `WizardView.vue:209-221`, `stores/shop/editor.ts:35-48`, `ProductEditor.vue:501-508`):** `promptId` sauber über `EditorDraft` an `addToCart` durchreichen (inkl. klarer „inkl. MwSt.“-Anzeige).
- **`C4` (`RoyalDogView.vue:13`, `ShopLayout.vue:13`):** Route `/royal-dog` und interne Verlinkungen bis nach dem Launch ausblenden.

---

## Abschluss-Checkliste

- [ ] `cd backend && bun run check` (falls Backend-Teile wie `A1`, `A4`, `A13` geändert wurden) läuft grün.
- [ ] `cd frontend && bun run build`, `bun run test:unit -- --run` und `bunx eslint .` laufen grün.
- [ ] Sowohl deutsche (`de`) als auch englische (`en`) Oberfläche auf konsistente Texte, Preise und Links geprüft.
- [ ] Alle erledigten Punkte in `GOLIVE-TODO.md` abgehakt.
