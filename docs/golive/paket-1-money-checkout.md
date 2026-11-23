# Paket 1: Geld, Warenkorb & Checkout-Integrität (Backend)

**Enthaltene Punkte aus `GOLIVE-TODO.md`:** `B2`, `B3`, `B4`, `C13` *(optional: Backend-API-Guard aus `A3`)*  
**Risikoprofil:** Hoch (Geld-, Preis-, Gutschein- und Bestelllogik). Gemäß `AGENTS.md` besonders sorgfältig testen und ggf. über den Council-Workflow absichern.  
**Parallelisierbar mit:** Paket 2, Paket 3, Paket 4 *(Paket 5 sollte nach diesem Paket laufen, da beide `backend/src/modules/order/payment/service.ts` berühren)*.

---

## Kontext & vorab zu lesende Guides

Lies vor Änderungen unbedingt die betroffenen Architektur-Guides — **zwei Fixes widersprechen explizit dokumentierten Entscheidungen** und müssen dort offen angepasst werden:

- `docs/dev/backend/packages/cart-package.md` *(insb. Zeilen 237–250: „Snapshots ändern sich nie“ — wird durch `B2` bewusst geändert!)*
- `docs/dev/backend/packages/checkout-package.md` *(insb. Zeilen 274–277: „PAID ohne Einlösung“ — wird durch `B3` bewusst verschärft!)*
- `docs/dev/backend/packages/order-package.md` (falls vorhanden)

---

## Offene Kurzentscheidung vor Start

- **`A3` (Optionaler API-Guard):** Soll `POST /api/cart/items` (`backend/src/modules/cart/routes.ts:35`) `imageId: null` bereits ablehnen (damit über die API keine unpersonalisierten Artikel ohne Motiv bestellt werden können — stützt § 312g Abs. 2 Nr. 1 BGB)? Im Zweifel vorab kurz mit Joe klären.

---

## Aufgaben im Detail

### 1. `B2` — Mengenerhöhung im Warenkorb aktualisiert Preis & prüft `purchasable` (`TODO #4`)
- **Dateien:**
  - `backend/src/modules/cart/service.ts:254-255`
  - `backend/src/modules/cart/repository.ts:149-158`
  - `docs/dev/backend/packages/cart-package.md:237-250`
- **Problem:** `updateQuantity` ändert nur `quantity` und `updated_at`, ohne `purchasable` zu prüfen oder `price_cents` / `prompt_price_cents` neu aus dem Katalog zu bestimmen. Eine 0-€-Zeile aus einer abgelaufenen 100-%-Aktion lässt sich auf Menge 99 erhöhen und kostenlos (`PAID`) bestellen.
- **Umsetzung:**
  - Bei einer Mengenerhöhung (oder jedem Mengenwechsel) den aktuellen Katalogeintrag (Artikel/Variante und ggf. Prompt) prüfen:
    - Ist der Artikel/die Variante nicht mehr `purchasable` oder fehlt ein gültiger Preis (`grossSalesPriceCents === null`), ablehnen (eindeutiger Domain-Fehler / HTTP 409).
    - Aktuellen `price_cents` und ggf. `prompt_price_cents` neu in die Warenkorbzeile schreiben.
  - Entscheidung in `docs/dev/backend/packages/cart-package.md` explizit aktualisieren (warum die alte Snapshot-Regel bei Mengenänderungen abgelöst wird).
  - Unit-/Integrationstests für: Mengenerhöhung nach Preisänderung, nach abgelaufener Aktion (0 € → Normalpreis) und bei deaktiviertem Artikel.

### 2. `B3` — Einmal-Gutschein bei „Erneut bezahlen“ neu reservieren (`TODO #5`)
- **Dateien:**
  - `backend/src/modules/order/payment/service.ts:206-252` (`startPayment`)
  - `backend/src/modules/order/transitions.ts:48-62,106-110`
  - `docs/dev/backend/packages/checkout-package.md:274-277`
- **Problem:** Ein Kunde kann mehrere `PENDING`-Bestellungen mit demselben Einmal-Gutschein anlegen, jeweils die Zahlung abbrechen (wodurch `transitions.ts:106-110` die Reservierung freigibt) und anschließend alle Bestellungen über `POST /api/checkout/orders/:orderId/payment` bezahlen. `startPayment` reserviert den Gutschein nicht neu, und `markPaid` setzt die Bestellung selbst dann auf `PAID`, wenn `redeem()` fehlschlägt.
- **Umsetzung:**
  - In `startPayment` unter Order-Lock prüfen, ob die Bestellung eine Promotion hat und ob aktuell eine aktive Reservierung existiert; falls nicht (z. B. nach vorherigem Zahlungsabbruch), die Promotion **vor** dem Mollie-Call neu reservieren.
  - Schlägt die Reservierung fehl (aufgebraucht, abgelaufen, pro-Nutzer-Limit erreicht), keine Mollie-Zahlung starten und `409` (`promotionRejected` o. ä.) zurückgeben.
  - `docs/dev/backend/packages/checkout-package.md:274-277` aktualisieren.
  - Tests für genau das Replay-Szenario: 2 Bestellungen mit Einmal-Code → beide abgebrochen → 1. Retry klappt & wird `PAID` → 2. Retry wird vor Mollie mit `409` abgelehnt.

### 3. `B4` — Deaktivierte / nicht kaufbare Artikel beim Placement ablehnen (`TODO #3`)
- **Dateien:**
  - `backend/src/modules/order/service.ts:141-144`
  - `backend/src/modules/article/catalog.ts:196`
- **Problem:** Beim Order-Placement wird nur `!catalog.has(catalogKey(line))` geprüft, nicht aber `purchasable === true` und `grossSalesPriceCents !== null`. Artikel, die nach dem Hinzufügen in den Warenkorb deaktiviert wurden oder ihren Preis/SPOD-Sync verloren haben, bleiben bestell- und bezahlbar und scheitern erst im Fulfillment.
- **Umsetzung:**
  - In `order/service.ts` jede Position auf `entry !== undefined && entry.purchasable === true && entry.grossSalesPriceCents !== null` prüfen und andernfalls mit klarem Konflikt-Fehler (wie `unknownArticle` / `unavailableArticle`, HTTP 409) ablehnen.
  - *(Bonus, geringes Risiko im selben Flow):* Prüfen, ob `checkout/service.ts:157` vs. `:110` den Warenkorb sauber nach `lockForCheckout()` liest (`TODO Kleinkram`).
  - Test ergänzen: Artikel im Warenkorb → im Katalog auf `purchasable = false` setzen → Checkout/Placement lehnt ab.

### 4. `C13` — Payment-Retry darf Bestellung bei kurzem Mollie-Fehler nicht dauerhaft stornieren
- **Dateien:**
  - `backend/src/modules/order/payment/service.ts:217-220`
  - `backend/src/modules/checkout/service.ts:192-213`
- **Problem:** Wenn `startPayment` beim initialen Checkout wegen Mollie-Timeout/5xx fehlschlägt, wird die `PENDING`-Bestellung storniert — dort unkritisch, weil der Warenkorb noch `ACTIVE` ist. Beim **Payment-Retry** (`POST /api/checkout/orders/:orderId/payment`) ist der Warenkorb aber bereits `CHECKED_OUT`: ein kurzer Mollie-Fehler storniert die Bestellung endgültig, und der Kunde strandet ohne Warenkorb und ohne zahlbare Bestellung.
- **Umsetzung:**
  - `startPayment` über expliziten Parameter (z. B. `cancelOnFailure: boolean` — `true` beim Erst-Checkout, `false` beim Retry) steuern, sodass Retry-Fehler bei Mollie die Bestellung auf `PENDING` belassen (ggf. eine in Schritt 2 frisch gezogene Promotion-Reservierung bei Mollie-Fehler sauber wieder freigeben!).
  - Test ergänzen: `PENDING`-Order nach abgebrochener Erstzahlung → Retry mit simuliertem Mollie-5xx/Timeout → Order bleibt `PENDING` und ein zweiter Retry gelingt.

---

## Nicht im Umfang (liegt in anderen Paketen)

- Kein Rate-Limiting (`B5`), keine HTTP-Header (`B6`) → **Paket 2**
- Keine Frontend-Anpassungen an `OrderConfirmationView` / `CartView` → **Paket 3**
- Keine Mollie-Refund-/Chargeback-Webhooks (`D2`) → **Paket 5**

---

## Abschluss-Checkliste

- [ ] `cd backend && bun run check` (`typecheck`, `lint`, `format:check`, `bun test` inkl. neuer Tests) läuft komplett grün.
- [ ] `docs/dev/backend/packages/cart-package.md` und `checkout-package.md` aktualisiert.
- [ ] Erledigte Punkte (`B2`, `B3`, `B4`, `C13`) in `GOLIVE-TODO.md` abgehakt.
- [ ] PR-Beschreibung dokumentiert die bewussten Änderungen an den bisherigen Design-Entscheidungen.
