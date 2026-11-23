# Paket 4: Backend-Betriebsreife, Migrationen, Docker & Cutover

**Enthaltene Punkte aus `GOLIVE-TODO.md`:** `C14`, `B11`, `C15`, `B12`, `C16`, `C18`, `C20`, `E1`, `C21` *(plus Backend-Kompression aus `C10`, falls nicht am Proxy aktiv)*  
**Risikoprofil:** Mittel (enthält 1 kleine DB-Migration `V40`, Docker-/Shutdown-Härtung und URL-Redirects).  
**Parallelisierbar mit:** Paket 1, Paket 3, Paket 5 *(kleine Schnittstelle zu Paket 2 in `backend/src/platform/http/app.ts` und `backend/src/platform/settings.ts` — bei Parallelbetrieb einfach kurz rebasen)*.

---

## Kontext & vorab zu lesende Dateien

- `backend/src/platform/settings.ts` & `backend/.env.example`
- `backend/src/main.ts`, `backend/src/composition.ts`, `backend/src/platform/http/app.ts`
- `backend/migrations/V16__create_orders.sql` & `backend/migrations/V5__create_email_jobs.sql`
- `backend/src/platform/db/migrate.ts` & `migrate.test.ts` *(Migrationszahl-Assertion aktualisieren!)*
- `Dockerfile`, `backend/Dockerfile`, `scripts/migrate-legacy-data.sh`

---

## Aufgaben im Detail

### 1. `C14` + `B11` + `C20` (Settings) — Sicherere Defaults & Startup-Konfigurationsprüfung
- **Dateien:**
  - `backend/src/platform/settings.ts:36-82`
  - `backend/.env.example`
  - `backend/src/main.ts`
- **Probleme:**
  - `DATABASE_MAXIMUM_POOL_SIZE` steht per Default auf `100` → droht bei Standard-Postgres (`max_connections=100`) und Rolling-Deploys sofort `too many clients already`.
  - `EMAIL_POLL_INTERVAL_MINUTES` steht per Default auf `5` → Kunden warten bis zu 5 min auf Bestellbestätigung.
  - Gefährliche Produktions-Defaults (`FRONTEND_BASE_URL=http://localhost:5173`, `EMAIL_ENABLED=false`, `DATABASE_SSL_MODE=disable`, `RATE_LIMIT_TRUST_FORWARDED_FOR=false`, `GENERATOR_DUMMY_MODE=true`) fallen beim Start nicht auf.
- **Umsetzung:**
  - Default von `DATABASE_MAXIMUM_POOL_SIZE` auf `20` senken.
  - Default von `EMAIL_POLL_INTERVAL_MINUTES` auf `1` senken (optional zusätzlich Worker bei `outbox.enqueue` direkt wecken, falls einfach und risikoarm).
  - Beim App-Start eine klare, secret-freie Zusammenfassung loggen (`frontendBaseUrl`, `secureCookies`, `emailEnabled`, `emailPollIntervalMinutes`, `databaseSslMode`, `databaseMaxPoolSize`, `rateLimitTrustForwardedFor`, `generatorDummyMode`, Mollie-Modus `live` vs. `test` anhand Key-Präfix) und explizite `WARN`-Logs ausgeben, wenn `FRONTEND_BASE_URL` `https://` ist, aber `DATABASE_SSL_MODE=disable`, `EMAIL_ENABLED=false`, `GENERATOR_DUMMY_MODE=true` oder `RATE_LIMIT_TRUST_FORWARDED_FOR=false` gesetzt ist.
  - `backend/.env.example` entsprechend aktualisieren.

### 2. `C15` — `/api/health`, `/api/ready` & `Dockerfile HEALTHCHECK`
- **Dateien:**
  - `backend/src/composition.ts:216-226`
  - `Dockerfile`
  - `backend/Dockerfile`
- **Problem:** `/` fällt über `serveFrontend` auf `200 index.html` zurück, selbst wenn PostgreSQL nicht erreichbar ist.
- **Umsetzung:**
  - Vor `serveFrontend` zwei schlanke Endpoints mounten (ohne Auth, ohne Rate-Limit):
    - `GET /api/health` → `200 { status: "ok" }` (reine Liveness).
    - `GET /api/ready` → führt `SELECT 1` mit kurzem Timeout (z. B. 2 s) aus; liefert `200 { status: "ready" }` bzw. `503 { status: "unavailable" }` bei DB-Fehler.
  - `HEALTHCHECK` im Root-`Dockerfile` (und `backend/Dockerfile`) gegen `/api/ready` ergänzen.
  - Tests für `/api/health` und `/api/ready` ergänzen.

### 3. `B12` — `magic-wormhole` aus Produktions-`Dockerfile` entfernen
- **Dateien:**
  - `Dockerfile:34-38`
- **Problem:** Das Produktions-Image installiert `magic-wormhole` (+ Python) — unnötige Image-Größe und Angriffsfläche.
- **Umsetzung:** Installation von `magic-wormhole` aus dem finalen Stage in `Dockerfile` entfernen.

### 4. `C16` — Graceful Shutdown härten & `spodCatalogSync` sauber beenden
- **Dateien:**
  - `backend/src/main.ts:38-51`
  - `backend/src/modules/article/spod-article/sync.ts:684-697`
  - `backend/src/composition.ts`
- **Problem:**
  1. Doppeltes `SIGTERM`/`SIGINT` ruft `shutdown()` zweimal parallel auf.
  2. `server.stop()` wartet ohne Timeout auf offene Keep-Alive-Verbindungen → Container bekommt `SIGKILL`, bevor Worker stoppen und `db.destroy()` läuft.
  3. Hintergrund-Promises von `spodCatalogSync` werden bei `application.stop()` nicht abgebrochen/abgewartet.
- **Umsetzung:**
  - Re-entrancy-Guard (`let shuttingDown = false`) in `shutdown()`.
  - `server.stop()` gegen Timeout (z. B. 10–15 s) racen und danach `server.stop(true)` erzwingen, anschließend Worker & `spodCatalogSync` (via `AbortController` / Drain-Handle) stoppen und `db.destroy()` ausführen.

### 5. `C18` + `C20` (Migration) — `ck_orders_owner` droppen & Partial Index auf `email_jobs`
- **Dateien:**
  - `backend/migrations/V40__go_live_db_hardening.sql` *(neue Migration; vorher höchste bestehende V-Nummer prüfen!)*
  - `backend/migrations/V16__create_orders.sql:65-66,81-84` (nur Referenz)
  - `backend/migrations/V5__create_email_jobs.sql` (nur Referenz)
  - `backend/src/platform/db/migrate.test.ts` (erwartete Migrationsanzahl prüfen/aktualisieren)
- **Probleme:**
  1. `orders.ck_orders_owner` verbietet `(user_id IS NULL AND guest_session_token IS NULL)` und kollidiert damit mit `fk_orders_user ON DELETE SET NULL` → DSGVO-Löschung eines registrierten Nutzers mit Bestellungen schlägt in PostgreSQL fehl!
  2. `email_jobs` hat keinen Partial Index für offene Jobs (`WHERE sent_at IS NULL`) → jeder Worker-/Operations-Scan macht einen Sequential Scan über alle historischen Payloads.
- **Umsetzung:**
  - In einer neuen Migration (`V40__...sql`):
    - `ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_owner;`
    - `CREATE INDEX IF NOT EXISTS ix_email_jobs_open ON email_jobs (id) WHERE sent_at IS NULL;`
  - Test ergänzen: Registrierter User mit Bestellung (ohne Gast-Token) lässt sich löschen, Bestellung bleibt mit `user_id = NULL` erhalten.

### 6. `E1` — Serverseitige 301-Redirects für alte WordPress-URLs
- **Dateien:**
  - `backend/src/composition.ts:209-225` (oder eigenes kleines Modul `backend/src/platform/http/legacy-redirects.ts`)
- **Problem:** Alte WordPress-URLs liefern aktuell per SPA-Fallback HTTP 200 + Client-404.
- **Umsetzung:**
  - Vor `serveFrontend` serverseitige `301`-Redirects (mit/ohne Trailing-Slash) einrichten:
    - `/impressum` → `/imprint`
    - `/widerruf` → `/returns`
    - `/versand` → `/shipping`
    - `/zahlung` → `/payment`
    - `/faq` → `/faq` *(oder `/` falls `/faq` entfernt wird — kurz prüfen)*
    - `/ueber-uns` → `/about` *(oder `/`)*
    - `/tassenuebersicht` → `/`
    - `/firmenkunden` → `/contact` *(oder `/`)*
    - `/pdf/AGBs.pdf` → `/terms`
  - Unit-Test für alle Redirects (`301` + erwarteter `Location`-Header).

### 7. `C21` — `scripts/migrate-legacy-data.sh` gegen versehentliches Löschen absichern (`TODO #16`)
- **Dateien:**
  - `scripts/migrate-legacy-data.sh:561-580`
- **Problem:** `TRUNCATE ... CASCADE` leert Ziel-DB inkl. Bestellungen/Zahlungen ohne Host-Check oder Bestätigung.
- **Umsetzung:** Abbrechen, wenn Ziel-Host nicht `localhost`/`127.0.0.1` ist, sofern nicht explizit `--yes-wipe-target` übergeben wurde (und kurze Warnung ausgeben).

### 8. *(Optional / Prüfen)* Backend-HTTP-Kompression (`C10`)
- **Dateien:** `backend/src/platform/http/app.ts`
- Prüfen, ob Hono `compress()` aus `hono/compress` aktiviert werden soll (hilft für JSON-APIs & statische Assets, falls Sliplane/Traefik nicht ohnehin komprimiert; sicherstellen, dass Binärbilder nicht unnötig komprimiert werden).

---

## Nicht im Umfang (liegt in anderen Paketen)

- Warenkorb-/Checkout-/Gutschein-Logik → **Paket 1**
- Auth-Rate-Limits, `secureHeaders()`, `fal.ai` → **Paket 2**
- Frontend-Änderungen → **Paket 3** / **Paket 6**
- SPOD-Webhook-Fallback, Retry-Backoff, Mollie-Chargebacks → **Paket 5**

---

## Abschluss-Checkliste

- [ ] `cd backend && bun run check` (`typecheck`, `lint`, `format:check`, `bun test` inkl. Migrations- & Redirect-Tests) läuft komplett grün.
- [ ] Docker-Build lokal (falls Docker verfügbar) oder `Dockerfile`-Syntax geprüft.
- [ ] Erledigte Punkte (`B11`, `B12`, `C14`–`C16`, `C18`, `C20` soweit umgesetzt, `C21`, `E1`) in `GOLIVE-TODO.md` abgehakt.
