# Go-Live-Dokumentation & Arbeitspakete (Stand 2026-11-25)

Gesamtübersicht und chronologischer Ablauf stehen im **[Go-Live-Fahrplan](fahrplan.md)** (Basis: [`GOLIVE-TODO.md`](../../GOLIVE-TODO.md)).  
Jedes Arbeitspaket ist so geschnitten, dass es in einer **eigenen, frischen Session** (bei Bedarf parallel in getrennten Worktrees) bearbeitet und als eigener PR gemergt werden kann.

## Paket-Übersicht

| Paket | Status | Datei | Schwerpunkt | Enthalten (`GOLIVE-TODO.md`) | Parallelisierbarkeit / Hinweis |
|---|---|---|---|---|---|
| **Paket 1** | Erledigt | [`paket-1-money-checkout.md`](paket-1-money-checkout.md) | Geld, Warenkorb & Checkout-Integrität (Backend) | `B2`, `B3`, `B4`, `C13`, `A3`-Guard | Gemergt (`4b98249`). |
| **Paket 2** | Erledigt | [`paket-2-security-auth-generator.md`](paket-2-security-auth-generator.md) | Security, Rate-Limits, Header & `fal.ai`-Schutz (Backend) | `B5`–`B10`, `A17` | Gemergt (`273e344`). `B13` in eigenes Paket 8 ausgelagert. |
| **Paket 3** | Erledigt | [`paket-3-frontend-blocker-ux.md`](paket-3-frontend-blocker-ux.md) | Frontend-Blocker, Conversion, PWA & SEO | `B1`, `B14`, `C3`, `C5`–`C12`, `A15` | Gemergt (`efe0675`). |
| **Paket 4** | Erledigt | [`paket-4-backend-betrieb-cutover.md`](paket-4-backend-betrieb-cutover.md) | Backend-Betriebsreife, Migrationen, Docker & Redirects | `B11`–`B12`, `C14`–`C16`, `C18`, `C20`-Teil, `C21`, `E1`-Code | Gemergt (`f611010`). |
| **Paket 5** | Erledigt | [`paket-5-fulfillment-chargebacks.md`](paket-5-fulfillment-chargebacks.md) | Fulfillment-Stabilität & Mollie-Refunds/Chargebacks | `C17`, `C19`, `D2`-Code | Gemergt (`1063e16`). |
| **Paket 6** | Offen | [`paket-6-recht-ui-mails.md`](paket-6-recht-ui-mails.md) | Rechtstexte, Kasse, Bestellmails, PAngV, GPSR & Katalog-UI (geteilt in **6A** ohne externe Texte & **6B** mit finalen Texten) | `A1`–`A5`, `A7`–`A8`, `A10`–`A11`, `A13`–`A14`, `A16`, `A18`, `A20`–`A21`, `C1`, `C2`, `C4` | **6A** sofort startbar; **6B** nach Text-Zulieferung & Paket 7. |
| **Paket 7** | Offen | [`paket-7-versandkosten-countries.md`](paket-7-versandkosten-countries.md) | Länderspezifische Versandkosten in `countries` (EU-Start) | `D3`-Code, `A21`-Daten | **DB-Migration + Geld → Council empfohlen.** Am besten vor Paket 6B (Schnittstelle in `CheckoutView.vue` / `CartSummary.vue`). |
| **Paket 8** | Offen | [`paket-8-security-db-secrets-admin.md`](paket-8-security-db-secrets-admin.md) | Verschlüsselte DB-Secrets (SFTP/SPOD) & Admin-Session-Revocation | `B13` | **DB-Migration + Security → Council empfohlen.** Vollständig parallel zu Paket 6 & 7. |

## Aktuelle Reihenfolge (ab 2026-11-25)

1. **Sofort parallel starten:**
   - Externe Vorlaufzeiten & Texte (siehe [Fahrplan Strang 1A](fahrplan.md#strang-1a-recht-eu-epr-externe-vorlaufzeiten--seiteninhalte-kritischer-pfad--sofort-anstoßen))
   - **Paket 7** (Versandkosten in `countries`)
   - **Paket 8** (Security `B13`)
   - **Paket 6A** (Rechtliche UI, GPSR, Stil-Aufpreis `C2`, `/royal-dog` ausblenden)
2. **Danach:** **Paket 6B** (finale DE/EN-Rechtstexte, Footer-Seiten, Bestellmails) + Katalogbereinigung + Infrastruktur/Monitoring + Release-Gate + Cutover.

## Punkte außerhalb der Code-Pakete (Admin / Betrieb / Recht extern)

Alle organisatorischen, inhaltlichen und Infrastruktur-Aufgaben (`A12`, `A19`, `C22`, `D1`, `D3`-EPR, `D4`, `E1`-DNS, `E2`–`E5`, `F1`–`F4`) sind vollständig phasenweise im **[Go-Live-Fahrplan](fahrplan.md)** eingeordnet.
