# Paket 2: Security, Rate-Limits, Header & `fal.ai`-Schutz (Backend)

**Enthaltene Punkte aus `GOLIVE-TODO.md`:** `B5`, `B6`, `B7`, `B8`, `A17`, `B9`, `B10` *(optional: `B13`)*  
**Risikoprofil:** Hoch (Auth, Rate-Limiting, Kosten-Schutz für `fal.ai`, DSGVO/Cookie-Minimierung).  
**Parallelisierbar mit:** Paket 1, Paket 3, Paket 5 *(kleine Schnittstelle mit Paket 4 in `backend/src/platform/http/app.ts` und `backend/src/platform/settings.ts` — bei Parallelbetrieb einfach kurz rebasen)*.

---

## Kontext & vorab zu lesende Guides

- `docs/dev/backend/packages/account-package.md` (Auth, Lockout, Registrierung, Passwort-Reset)
- `docs/dev/backend/packages/generator-package.md` (Magic Coins, `fal.ai`, Rate-Limiting)
- `docs/dev/backend/packages/image-package.md` (insb. Zeile 132 zu `/api/images/private`)

---

## Aufgaben im Detail

### 1. `B5` — Rate-Limits auf Auth- & Gutschein-Endpunkten (`TODO #8`)
- **Dateien:**
  - `backend/src/modules/account/routes.ts:202-259`
  - `backend/src/modules/cart/routes.ts:145-155`
  - `backend/src/platform/ratelimit/client-ip-rate-limiter.ts`
  - `backend/src/composition.ts`
- **Problem:** Login, Registrierung, Forgot-Password, Resend-Confirmation und `POST /api/cart/promotion` haben kein Rate-Limit → Sweego-Mail-Bombing, gezieltes Admin-Aussperren (15 Fehlversuche = 10 min Lockout), PBKDF2-CPU-DoS (600k Iterationen) und Gutschein-Brute-Force.
- **Umsetzung:**
  - Vorhandenen `createClientIpRateLimiter` mit sinnvollen, getrennten Limits (und konfigurierbar/testbar) vorschalten für:
    - `POST /api/auth/login`
    - `POST /api/auth/register`
    - `POST /api/auth/forgot-password`
    - `POST /api/auth/resend-confirmation`
    - `POST /api/cart/promotion`
  - Sicherstellen, dass `429` sauber im einheitlichen API-Fehlerformat geantwortet wird und bestehende Auth-/Cart-Tests nicht durch zu enge Test-Limits flaken.

### 2. `B6` — HTTP-Sicherheits-Header (`secureHeaders()`)
- **Dateien:**
  - `backend/src/platform/http/app.ts:24-56`
  - `backend/src/composition.ts:209-225`
- **Problem:** HSTS, `X-Content-Type-Options: nosniff`, Clickjacking-Schutz (`X-Frame-Options` / `frame-ancestors`), `Referrer-Policy` und CSP fehlen.
- **Umsetzung:**
  - Hono `secureHeaders()` in `app.ts` einbinden (auf lokale HTTP-Entwicklung vs. HTTPS-Produktion achten — HSTS nur wenn `secureCookies` / HTTPS aktiv, oder Standard-HSTS-Verhalten prüfen).
  - `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` (bzw. `SAMEORIGIN` prüfen — es gibt keine Embeds), `Referrer-Policy: strict-origin-when-cross-origin` global und `no-referrer` für sensible Token-Seiten (`/order/*`, `/confirm-email`, `/reset-password`, `/confirm-change-email`).
  - Prüfen, dass CSP (falls aktiviert) lokale `@fontsource`-Schriften, Canvas/Blob-URLs im Editor (`data:`/`blob:`) und iubenda-HTML (bereits gesäubert) nicht blockiert — lieber sauber testen als blind kaputte CSP ausrollen.

### 3. `B7` — Pre-Registration Account Takeover & unvollständiger Password-Reset (`TODO #9`)
- **Dateien:**
  - `backend/src/modules/account/service.ts:127-142`
  - `backend/src/modules/account/repository.ts:293-307`
- **Problem:**
  1. Registriert ein Angreifer vorab eine fremde E-Mail-Adresse (unbestätigt), bekommt das echte Opfer bei der Registrierung `409 emailTaken` und bestätigt per „Bestätigung erneut senden“ unwissentlich das Passwort des Angreifers.
  2. `resetPassword` setzt weder `email_confirmed = true` noch hebt es Lockouts (`failed_login_count = 0, locked_until = null`) auf.
- **Umsetzung:**
  - Bei `register` auf eine bereits existierende **unbestätigte** E-Mail den Passwort-Hash (und Namen/Confirmation-Token) überschreiben und eine neue Bestätigungsmail versenden.
  - In `resetPassword` zusätzlich `email_confirmed = true`, `failed_login_count = 0`, `locked_until = null` setzen.
  - Tests für beide Fälle ergänzen.

### 4. `B8` — `fal.ai`-Kosten-Schutz: Magic-Coins atomar vorab abbuchen + globales Stundenlimit (`TODO #10`)
- **Dateien:**
  - `backend/src/modules/generator/service.ts:70-78`
  - `backend/src/modules/generator/magic-coins.ts:43-71`
  - `backend/src/platform/ratelimit/client-ip-rate-limiter.ts:16-94`
  - `backend/src/platform/settings.ts` & `backend/src/composition.ts`
- **Problem:**
  1. **TOCTOU-Race:** `generate()` prüft `balance >= 1`, wartet 15–120 s auf `fal.ai` und ruft erst danach `coins.spend(owner)` auf (und ignoriert sogar `false`). 20 parallele Requests mit 1 Coin lösen 20 bezahlte `fal.ai`-Calls aus.
  2. **Kein globales Limit:** `20/h pro IP × 100.000 IPs` erlaubt bei rotierenden IPs unbegrenzte `fal.ai`-Kosten, und eine volle 100k-Tabelle sperrt alle neuen Nutzer für 1 h aus.
- **Umsetzung:**
  - 1 Coin **atomar vor** dem `fal.ai`-Aufruf abbuchen (`UPDATE magic_coins SET balance = balance - 1 WHERE ... AND balance >= 1 RETURNING balance`). Liefert das `0` Zeilen → sofort `insufficientCoins` (ohne `fal.ai`-Call).
  - Schlägt der `fal.ai`-Call mit `upstreamFailure` (oder internem Fehler vor Rückgabe) fehl, den 1 Coin atomar wieder gutschreiben (`+1`).
  - Prozessweites globales Stundenlimit im Generator-Limiter ergänzen (z. B. `GENERATOR_GLOBAL_MAX_PER_HOUR`, Default z. B. `200`, konfigurierbar in `settings.ts` und dokumentiert in `backend/.env.example`).
  - Concurrency-Test (2 parallele Requests bei Balance = 1 → genau 1 geht an den Provider, 1 lehnt ab) + Refund-bei-Fehler-Test + Global-Cap-Test.

### 5. `A17` — Kein 30-Tage-`voenix.guest`-Cookie bei passivem `GET /api/magic-coins/balance` (§ 25 TDDDG)
- **Dateien:**
  - `backend/src/modules/generator/routes.ts:85-88`
  - `backend/src/modules/generator/magic-coins.ts:19-26`
- **Problem:** `ShopLayout.vue:24` ruft auf *jeder* Seite (inkl. `/imprint`, `/privacy`) `GET /api/magic-coins/balance` auf. `magicCoinsOwner` nutzt `guestTokens.getOrCreate(c)` und setzt damit jedem passiven Besucher sofort einen 30-Tage-Cookie + DB-Zeile in `magic_coins`.
- **Umsetzung:**
  - Für `GET /api/magic-coins/balance`: `guestTokens.tryGet(c)` verwenden (analog zu `cart/routes.ts:69-70,86`). Gibt es weder eingeloggten User noch Gast-Cookie, einfach `{ balance: INITIAL_BALANCE }` (`10`) zurückgeben, **ohne** Cookie zu setzen und **ohne** DB-Eintrag anzulegen.
  - Erst bei tatsächlicher Nutzung (`POST /api/generator/generate`) wird das Gast-Cookie/die DB-Zeile angelegt.
  - Test: `GET /api/magic-coins/balance` ohne Cookie liefert `200` mit `10`, setzt keinen `Set-Cookie`-Header und erzeugt keine DB-Zeile.

### 6. `B9` — `/api/images/private/*` auf `requireAdmin` einschränken
- **Dateien:**
  - `backend/src/modules/image/routes.ts:34-43`
- **Problem:** `GET /api/images/private/:size/:filename{.+}` wird nur vom Admin-Orphan-UI (`frontend/src/stores/admin/orphanImages.ts:32-33`) verwendet, nutzt aber `requireAuthenticated` statt `requireAdmin`.
- **Umsetzung:** `privateImages.use(requireAuthenticated)` → `privateImages.use(requireAdmin)` ändern und im Test verifizieren (normaler Kunde bekommt `403`, Admin bekommt `200`).

### 7. `B10` — Upload- & Download-Validierung für `fal.ai` über `sharp`
- **Dateien:**
  - `backend/src/modules/generator/upload.ts:61-67`
  - `backend/src/modules/generator/fal.ts:112-136`
- **Problem:** Generator-Uploads prüfen nur den Client-`Content-Type`, nicht echte Magic-Bytes/Auflösung (≤40 MP) wie `image/codec.ts:82-111`; Ergebnis-Download aus `fal.ts` folgt Redirects und prüft keine Magic-Bytes.
- **Umsetzung:**
  - Hochgeladene Bytes vor dem `fal.ai`-Call über `sharp` (bzw. gemeinsame Validierungsfunktion aus `image/codec.ts`) prüfen.
  - Heruntergeladene Provider-Bytes auf gültiges Bildformat prüfen und Redirect-Verhalten absichern (nur `https:` / keine internen Ziele).

### 8. *(Optional in diesem Paket oder direkt danach)* `B13` — Admin-Session-Revocation & SFTP/SPOD-Secrets verschlüsseln
- Nur mitnehmen, wenn der PR dadurch nicht zu groß wird (sonst sauber als eigener Mini-PR):
  - `requireAdmin` in `backend/src/platform/auth/auth.ts` gegen DB-Rolle/Session-Version prüfen (`account-package.md` beachten!).
  - SFTP-Passwörter & SPOD-Tokens in `backend/src/modules/production/repository.ts` at-rest mit AES-256-GCM verschlüsseln.

---

## Nicht im Umfang (liegt in anderen Paketen)

- Warenkorb-/Gutschein-/Placement-Logik (`B2`–`B4`, `C13`) → **Paket 1**
- Service Worker Cache (`B1`) & `LoginView`-Redirect (`B14`) → **Paket 3**
- `Dockerfile` (`B12`) & Startup-Logging zu `DATABASE_SSL_MODE` / `RATE_LIMIT_TRUST_FORWARDED_FOR` (`B11`) → **Paket 4**

---

## Abschluss-Checkliste

- [ ] `cd backend && bun run check` + `bun audit` laufen grün.
- [ ] `backend/.env.example` um ggf. neue Rate-Limit-Variablen ergänzt.
- [ ] Erledigte Punkte (`B5`–`B10`, `A17`, ggf. `B13`) in `GOLIVE-TODO.md` abgehakt.
