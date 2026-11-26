# Paket 8: Security `B13` – Verschlüsselte DB-Secrets & Admin-Session-Revocation

**Enthaltene Punkte aus [`docs/golive/README.md`](README.md):** `B13`  
**Risikoprofil:** Hoch (Kryptografie, Auth-Middleware, DB-Migration). Gemäß `AGENTS.md` über ein **Driving Issue + Council-Workflow** umsetzen.  
**Parallelisierbar mit:** Paket 6A, Paket 6B, Paket 7 (keine Dateikonflikte im Frontend; minimale Schnittstelle in `backend/.env.example` / `settings.ts`).

---

## Kontext & vorab zu lesende Guides

- `docs/dev/backend/conventions/authentication-and-authorization.md`
- `docs/dev/backend/packages/account-package.md`
- `docs/dev/backend/packages/production-package.md`
- `docs/dev/backend/packages/spod-fulfillment.md`

---

## Aufgaben im Detail

### 1. SFTP-Passwörter & SPOD-Tokens at-rest verschlüsseln
- **Dateien:**
  - `backend/src/platform/settings.ts` & `backend/.env.example`
  - `backend/src/platform/crypto/` *(oder vorhandenes AES-256-GCM-Hilfsmodul aus `platform/auth/` verallgemeinern)*
  - `backend/src/modules/production/repository.ts`
  - `backend/migrations/` *(falls Spaltentyp/Format angepasst wird)*
- **Problem:** SFTP-Passwörter und SPOD-Live-API-Tokens liegen im Klartext in der PostgreSQL-Tabelle `production_destinations` (`sftp_password`, `spod_api_token` o. ä.). Ein DB-Backup-Leak oder SQL-Dump legt sofort externe Zugangsdaten offen.
- **Umsetzung:**
  - Eigenen symmetrischen Schlüssel per Env-Variable einführen (z. B. `PRODUCTION_SECRETS_KEY`, ≥32 Bytes, getrennt von `AUTH_SESSION_SECRET`; in Produktion verpflichtend, in Dev klar dokumentierter Default oder Fail-Fast).
  - Secrets vor dem Schreiben mit **AES-256-GCM** (zufällige 96-Bit-Nonce, Auth-Tag, versioniertes Prefix wie `enc:v1:...`) verschlüsseln und beim Lesen entschlüsseln.
  - Sicherstellen, dass Secrets niemals in Logs, Fehlerantworten oder Admin-List-Responses im Klartext erscheinen.
  - Saubere Behandlung bestehender/neu gespeicherter Werte (keine stillen Klartext-Fallbacks in Produktion; klarer Start-/Migrationspfad für bestehende Dev-/Prod-Daten).

### 2. DB-gestützte Admin-Prüfung (Session-Revocation)
- **Dateien:**
  - `backend/src/platform/auth/auth.ts`
  - `backend/src/modules/account/repository.ts`
  - `backend/src/composition.ts`
- **Problem:** `requireAdmin` verlässt sich aktuell allein auf die Rollen im signierten/verschlüsselten Stateless-Cookie. Wird einem Admin die Rolle entzogen, das Konto gesperrt/gelöscht oder das Passwort geändert, bleibt ein zuvor ausgestelltes Admin-Cookie bis zum Ablauf gültig.
- **Umsetzung:**
  - `requireAdmin` (und den Session-Pfad von `requireAdminOrToken`) gegen die Datenbank prüfen (analog zu `requireSupplier`): Existiert der Benutzer noch, hat er aktuell die Rolle `ADMIN`, und ist die Session noch gültig (z. B. `session_version` / `password_changed_at` oder Passwort-/Logout-Invalidierung prüfen)?
  - Tests für:
    - Admin verliert `ADMIN`-Rolle → bestehender Cookie erhält sofort `403`.
    - Admin-Konto wird gelöscht → bestehender Cookie erhält sofort `401`.
    - Passwortwechsel / Revocation → alter Admin-Cookie wird sofort abgelehnt.
    - Access-Token-Pfad (`/api/mcp` / `requireAdminOrToken`) bleibt unverändert sauber abgesichert.

---

## Abschluss-Checkliste

- [ ] `cd backend && bun run check` grün (inkl. Krypto-Roundtrip-, Manipulations- und Admin-Revocation-Tests).
- [ ] `backend/.env.example` und [`docs/golive/README.md`](README.md) (`E2`) um die neue Env-Variable für den Encryption-Key ergänzt.
- [ ] `docs/dev/backend/conventions/authentication-and-authorization.md` (und ggf. `production-package.md`) aktualisiert.
- [ ] Paket 8 aus [`docs/golive/README.md`](README.md) entfernen (und Datei löschen), sobald gemergt.
