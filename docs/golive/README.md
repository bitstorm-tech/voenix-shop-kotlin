# Go-Live Fix-Pakete (Stand 2026-11-23)

Aufgeteilte Arbeitspakete aus [`GOLIVE-TODO.md`](../../GOLIVE-TODO.md), damit jedes Paket sauber in einer **eigenen, frischen Session** (und bei Bedarf parallel in getrennten Worktrees) bearbeitet und als eigener PR gemergt werden kann.

## Paket-Übersicht

| Paket | Datei | Schwerpunkt | Enthalten (`GOLIVE-TODO.md`) | Parallelisierbarkeit |
|---|---|---|---|---|
| **Paket 1** | [`paket-1-money-checkout.md`](paket-1-money-checkout.md) | Geld, Warenkorb & Checkout-Integrität (Backend) | `B2`, `B3`, `B4`, `C13` (+ optional `A3`-API-Guard) | Parallel zu **Paket 2, 3, 4** (keine Dateikonflikte). **Geld-Logik → hohe Sorgfalt / Council prüfen.** |
| **Paket 2** | [`paket-2-security-auth-generator.md`](paket-2-security-auth-generator.md) | Security, Rate-Limits, Header & `fal.ai`-Schutz (Backend) | `B5`, `B6`, `B7`, `B8`, `A17`, `B9`, `B10` (+ optional `B13`) | Parallel zu **Paket 1, 3, 5**. Mit Paket 4 minimale Schnittstelle in `app.ts` / `settings.ts`. |
| **Paket 3** | [`paket-3-frontend-blocker-ux.md`](paket-3-frontend-blocker-ux.md) | Frontend-Blocker, Conversion, PWA & SEO | `B1`, `C10`, `C11`, `C5`, `C6`, `C7`, `C8`, `C9`, `C3`, `A15`, `C12`, `B14` | Vollständig parallel zu **Paket 1, 2, 4, 5** (reines Frontend). |
| **Paket 4** | [`paket-4-backend-betrieb-cutover.md`](paket-4-backend-betrieb-cutover.md) | Backend-Betriebsreife, Migrationen, Docker & Redirects | `C14`, `B11`, `C15`, `B12`, `C16`, `C18`, `C20`, `E1`, `C21` (+ ggf. Backend-Teil `C10`) | Parallel zu **Paket 1, 3, 5**. Kleine Schnittstelle zu Paket 2 (`app.ts` / `settings.ts`). |
| **Paket 5** | [`paket-5-fulfillment-chargebacks.md`](paket-5-fulfillment-chargebacks.md) | Fulfillment-Stabilität & Mollie-Refunds/Chargebacks | `C17`, `C19`, `D2` | Parallel zu **Paket 2, 3, 4**. Nach Paket 1 ausführen (berühren beide `order/payment/service.ts`). |
| **Paket 6** | [`paket-6-recht-ui-mails.md`](paket-6-recht-ui-mails.md) | Rechtstexte, Kasse, Bestellmails, PAngV, GPSR & Katalog-UI | `A1`–`A5`, `A7`–`A8`, `A10`–`A11`, `A13`–`A14`, `A16`, `A18`, `A20`–`A21`, `C1`, `C2`, `C4` | Am besten **nach Paket 3** (teilt sich `CheckoutView.vue` / `de.json`) und sobald Texte/Entscheidungen vorliegen. |

## Empfohlene Reihenfolge (2 Wellen)

- **Welle 1 (parallel startbar):** **Paket 1** (Money) + **Paket 2** (Security) + **Paket 3** (Frontend) + **Paket 4** (Betrieb/Cutover — `app.ts` nach Paket 2 kurz rebasen)
- **Welle 2:** **Paket 5** (Fulfillment & Chargebacks, nach Paket 1) + **Paket 6** (Recht/UI/Mails, nach Paket 3 & Text-Zulieferung)

## Punkte außerhalb der Code-Pakete (Admin / Betrieb / Recht extern)

Diese Punkte aus `GOLIVE-TODO.md` sind organisatorisch, inhaltlich oder reine Admin-/Infra-Aufgaben und bleiben außerhalb der 6 Code-Pakete:

- **Admin-Katalog & Daten:** `A12` (Marken-Stilnamen umbenennen), `C22` (Testdaten/Tippfehler bereinigen), `A19` (keine Streichpreis-Rabatte zum Launch aktivieren), `D3` (Versandländer im Admin auf DE-only beschränken).
- **Geschäftlich / Ops / Infrastruktur:** `D1` (Rechnungsstellung/Lexoffice/sevDesk klären), `D4` (Monitoring, Postgres-/Volume-Backups, Single-Replica-Zwang, `/admin/access-tokens` prüfen).
- **Launch-Tag & Extern:** `E2` (Produktiv-Env & Secret-Rotation, SPOD/SFTP live schalten), `E3` (Sweego SPF/DKIM/DMARC), `E4` (E2E-Live-Testbestellungen & Rollback-Plan), `E5` (Erste 48h Monitoring), `F1` (AVVs inkl. fal.ai), `F2` (LUCID/VerpackG), `F3` (EU-OSS/EPR), `F4` (fal.ai-Spend-Limit, Mollie-KYC, Support-SOP, BFSG-Doku).
