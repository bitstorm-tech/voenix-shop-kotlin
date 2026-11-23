# Paket 3: Frontend-Blocker, Conversion, PWA & SEO

**Enthaltene Punkte aus `GOLIVE-TODO.md`:** `B1`, `C10` (Frontend), `C11`, `C5`, `C6`, `C7`, `C8`, `C9`, `C3`, `A15`, `C12`, `B14`  
**Risikoprofil:** Mittel (reines Frontend — berührt keinerlei Backend-Dateien).  
**Parallelisierbar mit:** **Paket 1, Paket 2, Paket 4, Paket 5** komplett konfliktfrei! *(Paket 6 sollte nach diesem Paket laufen, da beide `CheckoutView.vue` und `de.json`/`en.json` anfassen.)*

---

## Kontext & vorab zu lesende Guides

- `frontend/AGENTS.md` (falls vorhanden) und `docs/dev/frontend/*` (insb. `frontend-api-conventions.md`, `image-uploads.md`)

---

## Aufgaben im Detail

### 1. `B1` + `C10` + `C11` — Service Worker, Bundle-Splitting & SEO (`vite.config.ts` / `public/`)
- **Dateien:**
  - `frontend/vite.config.ts:34-43,81,90-107`
  - `frontend/public/robots.txt`
  - `frontend/public/sitemap.xml` *(neu anlegen)*
- **Umsetzung:**
  - **`B1` (`TODO #7`, BLOCKER):** `/api/`-Eintrag aus `workbox.runtimeCaching` (`vite.config.ts:35-43`) komplett entfernen, damit der Service Worker niemals `/api/auth/me`, Bestellungen, Bestell-Links oder Admin-Daten über Logout hinaus cacht.
  - **`C10` (`TODO #17`):** Die Chunk-Gruppen `admin`, `shop` und `shared` aus `codeSplitting.groups` (`vite.config.ts:90-107`) entfernen (nur Vendor-Gruppen behalten, und ungenutztes `radix-vue` aus Zeile 81 entfernen), sodass Route-Lazy-Loading greift und der Storefront-Erstbesuch nicht das ~450 KB Admin-Bundle lädt.
  - **`C11`:** `frontend/public/sitemap.xml` mit den öffentlichen Haupt-URLs (`https://voenix.shop/`, `/imprint`, `/privacy`, `/shipping`, `/terms`, `/returns`, `/payment` — je nach aktiven Seiten) anlegen, `Sitemap: https://voenix.shop/sitemap.xml` in `frontend/public/robots.txt` ergänzen und `/^\/robots\.txt$/`, `/^\/sitemap\.xml$/` in `navigateFallbackDenylist` (`vite.config.ts:34`) aufnehmen.

### 2. `C5` — `OrderConfirmationView` zeigt Fehlerzustand bei fehlgeschlagener/abgebrochener Mollie-Zahlung
- **Dateien:**
  - `frontend/src/views/shop/OrderConfirmationView.vue:19-23,32-76,83-106,192-215`
  - `frontend/src/composables/useOrderStatusRefresh.ts:43-53`
  - `frontend/src/i18n/locales/de.json`, `en.json`
- **Problem:** Bei `FAILED`, `CANCELED` oder `EXPIRED` stoppt `useOrderStatusRefresh` das Polling und setzt `hasTerminalPayment = true`, aber `OrderConfirmationView` ignoriert das Flag und zeigt weiter „Bestellung eingegangen — Wir warten auf die Zahlungsbestätigung“. Außerdem wird `?orderId=` leer als `0` geparst (`Number("") === 0`).
- **Umsetzung:**
  - `hasTerminalPayment` auswerten und für `FAILED` / `CANCELED` / `EXPIRED` ein klares Fehler-Icon, passenden Titel/Text und prominenten „Erneut bezahlen“-Button anzeigen.
  - `?orderId=` strikt validieren: `typeof id === 'string' && /^[1-9]\d*$/.test(id)`.
  - Doppel-Submit beim Payment-Retry sperren (`isRedirecting` erst im `catch` zurücksetzen, nicht vor `window.location.href`).

### 3. `C6` — Mobile-Editor: „In den Warenkorb“-Text auf Smartphones (`<= 430px`) sichtbar halten
- **Dateien:**
  - `frontend/src/components/shop/editor/ProductEditor.vue:527-538`
- **Problem:** `max-[430px]:sr-only` versteckt das Button-Label auf iPhone SE/14/15 (375–390 px) und Pixel (412 px) → der wichtigste Kauf-Button schrumpft zu einem unbeschrifteten 36×36-Icon.
- **Umsetzung:** Label auch unter `430px` klar lesbar halten (z. B. volle Breite oder sauberes mobiles Button-Layout ohne `sr-only`).

### 4. `C7` — GIF aus Upload-Step entfernen
- **Dateien:**
  - `frontend/src/components/shop/wizard/steps/UploadImageStep.vue:12`
  - `frontend/src/stores/shop/wizard.ts:5`
  - `frontend/src/components/shared/ImageUploadDropzone.vue:25` (prüfen, ob auch im Admin genutzt — dort ggf. separat behandeln)
  - `frontend/src/i18n/locales/de.json:1095`, `en.json`
- **Problem:** Upload erlaubt GIF, aber der KI-Generator lehnt GIF mit `400` ab (`imageGeneration.ts:31`).
- **Umsetzung:** `image/gif` aus den akzeptierten Typen und Hilfetexten für den Shop-Upload entfernen.

### 5. `C8` — Wizard-Motiv-Reset & Warenkorb-Ladefehler (`TODO #12, #13`)
- **Dateien:**
  - `frontend/src/stores/shop/imageGeneration.ts:61-135`
  - `frontend/src/views/shop/WizardView.vue`
  - `frontend/src/components/shop/wizard/steps/GenerateStep.vue:132-143`
  - `frontend/src/views/shop/CartView.vue:39-125`
  - `frontend/src/views/shop/CheckoutView.vue:46-50`
- **Umsetzung:**
  - Generationszähler (oder `AbortController`) in `imageGeneration.ts`, der in `reset()` hochzählt und verspätete Antworten verwirft; in `WizardView.vue` bei Änderung von Artikel, hochgeladenem Foto oder Stil `imageGenerationStore.reset()` aufrufen.
  - In `CartView.vue` bei `cartStore.error && cartStore.isEmpty` eine Fehlerkarte mit „Neu laden“-Button statt „Dein Warenkorb ist leer“ zeigen; in `CheckoutView.vue` nur dann zu `/cart` umleiten, wenn `cartStore.isEmpty && !cartStore.error`.

### 6. `C9` — Concurrent-Fetch-Deduplizierung in Shop-Stores
- **Dateien:**
  - `frontend/src/stores/shop/prompts.ts:65-66`
  - `frontend/src/stores/shop/catalog.ts:221-222`
  - `frontend/src/stores/shop/articleCategories.ts:37-40`
- **Problem:** `if (isLoading.value) return` gibt sofort `undefined` zurück, ohne auf das laufende Promise zu warten → Klick auf einen Stil auf `/` während des Ladens verwirft `?promptId=`.
- **Umsetzung:** Laufendes `pendingRequest: Promise<void> | null` speichern und zurückgeben (analog zum Muster in `frontend/src/stores/shop/countries.ts:72-81`).

### 7. `C3` — Magic Coins Kauf-UI & Header-Links ausblenden
- **Dateien:**
  - `frontend/src/components/shop/Header.vue:110-116`
  - `frontend/src/components/shop/MobileMenu.vue:376-384`
  - `frontend/src/views/shop/MagicCoinsView.vue:63-78`
  - `frontend/src/components/shop/wizard/steps/GenerateStep.vue:276-280,345-349`
- **Problem:** Header und MobileMenu verlinken für alle Besucher auf `/magic-coins` mit Paketpreisen (7,90–34,90 €), obwohl der Kaufbutton nur einen Platzhalter-Toast anzeigt (zudem löscht die Navigation aus `GenerateStep` das hochgeladene Foto und kaufbare digitale Coins würden § 356a BGB auslösen).
- **Umsetzung:** Header-/MobileMenu-Links und Refill-CTA in `GenerateStep` ausblenden (bzw. nur den aktuellen Coin-Stand ohne Kauf-Link anzeigen) und die Kaufpakete auf `/magic-coins` entfernen/deaktivieren, bis ein echter Checkout existiert.

### 8. `A15` — Englischer Bestellbutton korrigieren (§ 312j Abs. 3 & 4 BGB)
- **Dateien:**
  - `frontend/src/i18n/locales/en.json:804`
- **Umsetzung:** `"submit": "Place Order"` ändern zu `"Order with obligation to pay"` (oder `"Buy now"`).

### 9. `C12` + `B14` — UX-, Robustheits- & i18n-Feinschliff
- **Doppelklick-Schutz (`TODO #19, #20`):**
  - `CartLineItem.vue:158-180` & `CartView.vue:55,82-88`: `+/-` und Löschen pro Zeile sperren, solange Request läuft; `clearCart` während Request sperren.
  - `CheckoutView.vue:268-276`: lokales `isRedirecting`-Flag, das im Erfolgsfall nicht vor dem Mollie-Redirect zurückgesetzt wird.
- **Nicht verfügbare Artikel im Warenkorb:** `CartView.vue:70-76` / `CartSummary.vue:68-77` → „Zur Kasse“-Button mit `:disabled="cartStore.hasUnavailableItem"` sperren.
- **Auth & Redirect (`B14` + `TODO Kleinkram`):**
  - `LoginView.vue:55-56`: `route.query.redirect` prüfen (`typeof raw === 'string' && /^\/(?!\/)/.test(raw)`).
  - `RegisterView.vue:110-116`: `minlength="8"` am Passwort-Feld ergänzen.
  - `stores/shared/auth.ts:186-193`: nur bei `ApiError` mit Status `401` ausloggen, nicht bei transienten `5xx`/Netzwerkfehlern.
- **Fehlertexte, `localStorage` & kleinere Bugs:**
  - Rohe englische `err.message` bei unbekannten/5xx-Fehlern durch lokalisierte Fallback-Meldungen ersetzen (`OrderView.vue`, `CartView.vue`, `CheckoutView.vue`, `LoginView.vue`, `RegisterView.vue`, `ProfileView.vue`).
  - `localStorage`-Zugriffe in `src/i18n/index.ts:9`, `stores/shared/locale.ts:12,17` und `stores/shared/theme.ts:31,39` mit `try/catch` kapseln.
  - `LandingCarousel.vue:112-115`: `ResizeObserver` über `watch(trackRef)` anhängen.
  - Kleine i18n-/A11y-Reste: `HeaderSubcategoryMenuCard.vue:42` („No image“), `TextToolPanel.vue:244` („deg“ → `°`), `MobileMenu.vue:148,157` (`aria-label`/`close-label`), `CartButton.vue:10`, `LanguageDropdown.vue:26`, `CheckoutView.vue:417` Fallback für `articleName === null`, `OrderDetails.vue:152` („Kostenlos“ statt `0,00 €`).
  - Ungenutzte Abhängigkeiten & toten Code entfernen (`TODO #18`): `@iconify/vue`, `@tanstack/vue-table`, `radix-vue`, `vite-plugin-vue-devtools` in `frontend/package.json` prüfen & entfernen, ungenutzte `frontend/src/composables/useImageContainRect.ts` löschen.

---

## Nicht im Umfang (liegt in anderen Paketen)

- Rechtstexte (`/terms`, `/returns`, `/payment`, `/imprint`), PAngV-Hinweise, GPSR, Fake-Reviews, Foto-Upload-Hinweis, Stil-Aufpreis (`C2`), `/royal-dog` (`C4`) → **Paket 6**
- Backend-Änderungen jeglicher Art → **Pakete 1, 2, 4, 5**

---

## Abschluss-Checkliste

- [ ] `cd frontend && bun run build` (inkl. `vue-tsc` + `vite build`) läuft fehlerfrei; Bundle-Größen in `dist/assets/` prüfen (Admin-Chunk ist vom Storefront getrennt!).
- [ ] `cd frontend && bun run test:unit -- --run` und `bunx eslint .` (ohne `--fix`) sowie `bun audit` laufen sauber durch.
- [ ] Erledigte Punkte (`B1`, `B14`, `A15`, `C3`, `C5`–`C9`, Frontend-Teil `C10`, `C11`, `C12`) in `GOLIVE-TODO.md` abgehakt.
