# Paket 5: Fulfillment-Stabilität & Mollie-Refunds/Chargebacks (Backend)

**Enthaltene Punkte aus `GOLIVE-TODO.md`:** `C17`, `C19`, `D2`  
**Risikoprofil:** Mittel bis Hoch (Produktions-/Fulfillment-Pipeline, SPOD-Webhooks, Mollie-Webhook-Verarbeitung).  
**Parallelisierbar mit:** Paket 2, Paket 3, Paket 4. **Empfehlung: nach Paket 1 ausführen**, da beide `backend/src/modules/order/payment/service.ts` bearbeiten.

---

## Kontext & vorab zu lesende Guides

Lies vor Änderungen unbedingt die zugehörigen Guides — **`C19` ändert dokumentiertes Retry-Verhalten** und muss dort explizit fortgeschrieben werden:

- `docs/dev/backend/packages/spod-fulfillment.md` *(insb. Zeilen 58–68 zu Retries & SPOD-Webhook-Matching)*
- `docs/dev/backend/packages/email-package.md` *(insb. Zeile 277 zu fehlendem Retry-Cap)*
- `docs/dev/backend/packages/payment-package.md` / `order-package.md` (falls vorhanden)

---

## Aufgaben im Detail

### 1. `C17` — SPOD-Webhook-Fallback & Lieferanten-PDF-Snapshot (`TODO #14, #15`)
- **Dateien:**
  - `backend/src/modules/production/fulfillment/service.ts:206-218`
  - `backend/src/modules/production/delivery/generation.ts:63-68`
  - `backend/src/modules/production/pdf/renderer.ts:83`
  - Referenz: `backend/src/modules/production/spod/submitter.ts:84-95` (`ITEM_SET_CHANGED`)
- **Probleme:**
  1. **SPOD-Webhook-Fallback (`TODO #15`):** Wenn nach einem Timeout zwei SPOD-Orders dieselbe Referenz `ORD-{orderId}-JOB-{jobId}` tragen und die verwaiste storniert wird, fällt `resolveWebhookJob` auf die Referenz-Suche zurück, ohne zu prüfen, ob die gespeicherte `external_reference` leer ist → kann den **aktiven** Produktionsjob stornieren!
  2. **Lieferanten-PDF-Snapshot (`TODO #14`):** Die PDF-Generierung filtert `order.items` nach dem *aktuellen* `item.supplierId` statt gegen `production_job_items` abzugleichen. Wird ein Artikel zwischen Split und Generierung einem anderen Lieferanten zugeordnet, fehlen Zeilen stillschweigend im PDF.
- **Umsetzung:**
  - In `fulfillment/service.ts`: Den `parseSpodOrderReference`-Fallback nur zulassen, wenn die am gefundenen Job gespeicherte `externalReference` `null` ist (oder exakt mit der eingehenden SPOD-ID übereinstimmt). Andernfalls ignorieren/loggen statt fremden Job zu überschreiben.
  - In `delivery/generation.ts` / `pdf/renderer.ts`: Vor dem Rendern prüfen, dass die selektierten `order.items` exakt dem in `production_job_items` gespeicherten Set entsprechen — bei Abweichung wie in `spod/submitter.ts:84-95` mit `ITEM_SET_CHANGED` fehlschlagen und Operations-Finding erzeugen.
  - Tests für beide Fehlerszenarien ergänzen.

### 2. `C19` — Backoff / Stopp bei permanenten Fehlern (Email `4xx` & SPOD `ORDER_CREATE_REJECTED`)
- **Dateien:**
  - `backend/src/modules/email/worker.ts:39-64` (und `email/repository.ts` / Schema falls Status/Attempt-Spalte nötig)
  - `backend/src/modules/production/spod/repository.ts:103-129,151-162`
  - `docs/dev/backend/packages/email-package.md:277`
  - `docs/dev/backend/packages/spod-fulfillment.md:58-68`
- **Probleme:**
  1. Email-Jobs, die Sweego mit `4xx` ablehnt (z. B. ungültige Empfänger-Syntax `PROVIDER_HTTP_400`), laufen im Intervall endlos weiter und halten `EMAIL_STALLED` für immer offen.
  2. `spod/repository.ts:openJobs()` schließt `PERMANENT_FAILURES` (z. B. `ORDER_CREATE_REJECTED`) nicht aus → abgelehnte SPOD-Jobs senden alle 60 s endlos weiter `POST /orders` gegen SPODs 60 req/min Rate-Limit (und bei wechselndem permanenten/temporären Fehler feuern Ops-Mails immer wieder neu — `TODO Kleinkram`).
- **Umsetzung:**
  - **Email:** Permanente `4xx`-Antworten (außer `408`/`429`) bzw. Überschreiten eines Max-Attempt-Limits klar markieren (Dead-Letter / `failed_permanently` oder Exponential Backoff + Stopp), sodass sie nicht mehr im 1-Minuten-Takt gegen Sweego laufen; Ops-Finding/Sichtbarkeit in `/admin/operations` sauber halten.
  - **SPOD:** Permanente Fehler (`ORDER_CREATE_REJECTED` etc.) aus der automatischen 60-s-Retry-Schleife herausnehmen (oder stark backoffen / erst nach Admin-Eingriff erneut versuchen) und wiederholte Ops-Mails beim Wechsel permanent ↔ temporär unterdrücken (`spod/repository.ts:151-162`).
  - Beide Guides (`email-package.md`, `spod-fulfillment.md`) explizit auf das neue Verhalten aktualisieren.
  - Unit-/Integrationstests für beide Worker-Pfade.

### 3. `D2` — Mollie-Refunds & Chargebacks im Webhook erkennen + Ops-Alert
- **Dateien:**
  - `backend/src/modules/order/payment/mollie.ts:13-21,64-69`
  - `backend/src/modules/order/payment/service.ts:125-156`
  - `backend/src/modules/operations/` (Finding/Alert für Chargebacks prüfen)
- **Problem:** Webhooks für bereits auf `PAID` stehende Zahlungen werden ignoriert, und `amountRefunded` / `amountChargedBack` aus der Mollie-API werden nicht geparst. Eine Rückbuchung (Chargeback) fällt im Shop nicht auf — im schlimmsten Fall wird trotz Chargeback noch produziert/versendet.
- **Umsetzung:**
  - In `mollie.ts` die Felder `amountRefunded` und `amountChargedBack` aus der Mollie-Payment-Antwort parsen.
  - Im Webhook-Handler (`order/payment/service.ts`) auch bei bereits `PAID`-Zahlungen prüfen, ob neue Refund- oder Chargeback-Beträge vorliegen:
    - Idempotent `PAYMENT_REFUNDED` / `PAYMENT_CHARGEBACK` Events in `order_events` protokollieren (so sichtbar in `OrderTimelineView.vue`!).
    - Bei Chargeback ein Operations-Finding / Ops-Alert auslösen, damit sofort eingegriffen werden kann.
  - Tests für Refund-Webhook, Chargeback-Webhook und Idempotenz bei doppeltem Webhook-Aufruf.

---

## Nicht im Umfang (liegt in anderen Paketen)

- Warenkorb-/Checkout-Fixes (`B2`–`B4`, `C13`) → **Paket 1**
- Vollständige Admin-UI zum Auslösen von Refunds/Stornos (zum Launch reicht manuelle Auslösung im Mollie-Dashboard + Sichtbarkeit/Alert im Shop; bei Bedarf eigener Post-Launch-PR)
- Rechtliche Mails (`A4`) → **Paket 6**

---

## Abschluss-Checkliste

- [ ] `cd backend && bun run check` läuft komplett grün (inkl. neuer Tests für SPOD, PDF-Snapshot, Email-4xx und Mollie-Chargebacks).
- [ ] `docs/dev/backend/packages/email-package.md` und `spod-fulfillment.md` aktualisiert.
- [ ] Erledigte Punkte (`C17`, `C19`, Code-Teil von `D2`) in `GOLIVE-TODO.md` abgehakt.
