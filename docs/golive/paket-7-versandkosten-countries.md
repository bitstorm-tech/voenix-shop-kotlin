# Paket 7: Länderspezifische Versandkosten in `countries`

**Enthaltene Punkte aus `GOLIVE-TODO.md`:** `D3` (Code-Teil), `A21` (dynamische Länder- & Versandkostenanzeige)  
**Risikoprofil:** Hoch (DB-Migration, Geld-/Checkout-Logik, modulübergreifend über `country`, `cart`, `checkout` und Frontend). Gemäß `AGENTS.md` über ein **Driving Issue + Council-Workflow** umsetzen.  
**Abhängigkeiten:** Am saubersten **vor Paket 6B** (und abgestimmt mit Paket 6A in `CheckoutView.vue` / `CartSummary.vue`), damit `/shipping` und Checkout direkt die echten Länder-Versandkosten nutzen.

---

## Kontext & vorab zu lesende Guides

- `docs/dev/backend/packages/country-package.md`
- `docs/dev/backend/packages/cart-package.md`
- `docs/dev/backend/packages/checkout-package.md`
- `docs/dev/frontend/api-contract-map.md`

---

## Hintergrund & Ziel

Der Shop startet in mehreren europäischen Ländern. Bisher rechnet `backend/src/modules/cart/totals.ts` mit harten Konstanten (`SHIPPING_COST_CENTS = 490`, `FREE_SHIPPING_THRESHOLD_CENTS = 5_000`) für alle Länder gleich. Mit diesem Paket werden die Versandkonditionen direkt pro Land in der Tabelle `countries` gepflegt, im Checkout serverseitig anhand des gewählten Lieferlandes berechnet und auf `/shipping` dynamisch ausgespielt.

---

## Aufgaben im Detail

### 1. Datenbank & `country`-Modul (Backend)
- **Dateien:**
  - `backend/migrations/V42__country_shipping_costs.sql` *(nächste freie Migrationsnummer)*
  - `backend/src/modules/country/schema.ts`
  - `backend/src/modules/country/repository.ts`
  - `backend/src/modules/country/routes.ts`
  - `backend/src/modules/country/index.ts`
- **Umsetzung:**
  - `countries` um länderspezifische Versandkonditionen erweitern (z. B. `shipping_cost_cents integer not null check (>= 0)` und `free_shipping_threshold_cents integer check (> 0)` – `null` = kein kostenloser Versand ab Schwellenwert; ggf. auch Lieferzeitangaben abstimmen).
  - Bestehende Seed-/Bestandszeilen sauber migrieren (sinnvolle Defaults setzen).
  - Admin-API (`POST`/`PUT /api/admin/countries`) validiert und speichert die neuen Felder; Public-API (`GET /api/countries` → `toPublicCountry`) liefert die Versandkonditionen mit aus.
  - Capability für `checkout` erweitern (z. B. `findByCode(countryCode)` / `shippingPolicy(countryCode)` statt bloßem `isShippable(code)`).

### 2. `cart`- & `checkout`-Berechnung (Backend)
- **Dateien:**
  - `backend/src/modules/cart/totals.ts`
  - `backend/src/modules/cart/service.ts`
  - `backend/src/modules/checkout/service.ts`
  - `backend/src/modules/checkout/routes.ts`
- **Umsetzung:**
  - `cartTotals(lines, discount, shippingPolicy)` akzeptiert die länderspezifische Versandregel statt globaler Konstanten (`subtotal <= 0` → `0`; `threshold !== null && subtotal >= threshold` → `0`; sonst `shippingCostCents`).
  - Beim Checkout-Placement (`checkout/service.ts`) wird die Versandregel des `shippingAddress.country` serverseitig aus `countries` geladen, in `cartTotals` angewendet und als unveränderlicher Snapshot auf `orders.shipping_cost_cents` / `orders.total_cents` gespeichert.
  - Beachten: Ein prozentualer oder fester Gutschein wirkt weiterhin auf `subtotal + shipping` — serverseitige und clientseitige Neuberechnung bei Länderwechsel müssen exakt übereinstimmen.
  - Klare Regel für `GET /api/cart` (solange noch kein Lieferland feststeht): z. B. Versandkosten im Warenkorb explizit als geschätzt/DE-Basis kennzeichnen oder erst nach Länderwahl verbindlich berechnen (konsistent mit `CartSummary.vue`).

### 3. Admin-UI & Storefront (Frontend)
- **Dateien:**
  - `frontend/src/views/admin/` & `frontend/src/composables/useAdminCountries.ts`
  - `frontend/src/stores/shop/` (Country-Store / API-Typen)
  - `frontend/src/components/shop/CartSummary.vue`
  - `frontend/src/views/shop/CheckoutView.vue`
  - `frontend/src/views/shop/ShippingView.vue`
  - `frontend/src/i18n/locales/de.json`, `en.json`
- **Umsetzung:**
  - **Admin (`/admin/countries`):** Eingabefelder & Tabellenanzeige für Versandkosten und Versandfreigrenze je Land.
  - **Checkout (`CheckoutView.vue`):** Ändert der Kunde das Lieferland, aktualisieren sich Versandkosten, Gutschein-Betrag und Gesamtsumme sofort live (identisch zur Backend-Formel).
  - **Warenkorb (`CartSummary.vue`):** Keine irreführende pauschale „4,90 €“-Aussage mehr, falls der Kunde ins EU-Ausland bestellt — transparenter Hinweis auf landabhängige Versandkosten bzw. Länderwahl + Link auf `/shipping`.
  - **Versandseite (`ShippingView.vue`):** Länderliste, Versandkosten und Freigrenzen dynamisch aus `GET /api/countries` anzeigen statt aus statischen Locale-Arrays.

---

## Abschluss-Checkliste

- [ ] `cd backend && bun run check` grün (inkl. Tests für DE vs. EU-Versand, Freigrenze `null` vs. gesetzt, Gutschein + EU-Versand, ungültiges Land).
- [ ] `cd frontend && bun run build`, `bun run test:unit -- --run` und `bunx eslint .` grün.
- [ ] `docs/dev/backend/packages/country-package.md`, `cart-package.md` und `checkout-package.md` aktualisiert.
- [ ] Erledigte Teile von `D3` / `A21` in `GOLIVE-TODO.md` dokumentiert.
