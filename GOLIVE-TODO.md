# GOLIVE-TODO – Launch-Audit 2026-11-22 (Fortschreibung von 2026-11-17)

Ergebnis der vollständigen Code- und Go-Live-Prüfung (Recht, Security, Backend, Frontend, Betrieb/Cutover) auf Basis des aktuellen Stands, inkl. Abgleich mit dem Audit vom 17.11.2026 und `TODO.md` (02.11.2026).
Die rechtlichen Punkte sind eine technische Einschätzung, keine Rechtsberatung.
Prioritäten: **A, B und die Blocker in C/D vor dem Go-live**, übrige Punkte in C/D vor Werbung und echtem Traffic, **E am Go-live-Tag**, **F organisatorisch außerhalb des Codes**.

---

## A. Rechtliche Blocker

### Bestehende Punkte (Stand 2026-11-22 verifiziert)

- [ ] **A1. Datenschutzerklärung prüfen & absichern** *(teilweise erledigt)* – `backend/src/modules/legal/index.ts`, `frontend/src/views/shop/PrivacyView.vue`
  `/api/legal/privacy` proxyt jetzt serverseitig die iubenda-Policy `48736531` und entfernt externe `img`/`svg` (kein IP-Leak an iubenda).
  Noch offen:
  - Inhalt der Policy `48736531` bei iubenda verifizieren: Sliplane (Hosting), Mollie, Sweego (Mail), **fal.ai (Foto-Upload, US-Drittlandtransfer!)**, SPOD und Tassen-Lieferant, Cookies (`voenix.auth`, `XSRF-TOKEN`, `voenix.guest`), `localStorage`, Kundenkonto; alte Einträge (Stripe, Tag Manager, Facebook-Pixel) müssen raus.
  - Kein lokaler Fallback bei Cold-Start/iubenda-Ausfall (liefert dann HTTP 502) und keine englische Fassung für `en`-Locale.
- [ ] **A2. AGB fehlen auf der Seite** – `frontend/src/views/shop/TermsView.vue`, `frontend/src/views/shop/CheckoutView.vue:463-469`
  `/terms` ist weiterhin ein Platzhalter. Die Kasse verlinkt noch auf `https://voenix.shop/pdf/AGBs.pdf` beim alten WordPress-Host (nach Domain-Umzug tot; PDF Stand 18.09.2025 ist veraltet: falsche Adresse/Mail, widersprüchliche Lieferzeit, leere Ziffer 4.4, unzulässige Rügepflicht 9.2, fehlender Verbrauchervorbehalt, fehlende Angaben zu Vertragstext-Speicherung/Vertragssprache).
  Fix: AGB neu erstellen lassen, auf `/terms` anzeigen, Kasse per `RouterLink` auf `/terms` verlinken.
- [ ] **A3. Widerrufsinfo fehlt** *(API-Guard erledigt)* – `frontend/src/views/shop/ReturnsView.vue`, `frontend/src/views/shop/CheckoutView.vue:458-473`
  `/returns` ist ein Platzhalter; in der Kasse fehlt der Hinweis auf den Ausschluss des Widerrufsrechts für personalisierte Ware (§ 312g Abs. 2 Nr. 1 BGB / Art. 246a § 1 Abs. 3 Nr. 1 EGBGB).
  Fix: Text auf `/returns`, Hinweis mit Link in der Kasse.
  Hinweis zum **Widerrufsbutton (§ 356a BGB, Pflicht seit 19.06.2026)**: nicht nötig, solange ausschließlich personalisierte Ware verkauft wird — `imageId` in `backend/src/modules/cart/routes.ts` ist jetzt Pflichtfeld (erledigt); `/magic-coins`-Kaufpakete noch in Paket 3 ausblenden.
- [ ] **A4. Bestellbestätigung ohne Vertragsinfos & Anbieter-Footer** – `backend/src/modules/email/templates/order-confirmation.ts:78-145`, `backend/src/modules/email/templates/layout.ts:83,121-123`
  Mail enthält weder AGB noch Widerrufshinweis, kein `inkl. MwSt.`, keine Lieferzeit; Footer zeigt nur `© Voenix Shop` statt vollständiger Anbieterangaben (§ 312f Abs. 2 BGB dauerhafter Datenträger).
  Fix: AGB und Widerrufshinweis als Text/Anhang, `inkl. gesetzl. MwSt.` + Lieferzeit sowie vollständige Anbieterangaben (GbR, Gesellschafter, Anschrift, E-Mail, Telefon, USt-IdNr.) in den Mail-Footer.
- [ ] **A5. Impressum unvollständig** – `frontend/src/views/shop/ImprintView.vue:47-70`, `frontend/src/views/shop/ContactView.vue`
  Telefonnummer fehlt weiterhin (Pflicht im Fernabsatz, Art. 246a § 1 Abs. 1 Nr. 2 EGBGB / § 5 DDG); § 36 VSBG-Hinweis fehlt ebenfalls (keinen abgeschalteten EU-OS-Link setzen). `/contact` ist ein Platzhalter.
  Fix: Telefonnummer und § 36 VSBG ergänzen.
- [x] **A6. Google Fonts von Google-Servern** – `frontend/index.html`, `frontend/src/assets/base.css:3-4`, `frontend/src/components/shop/editor/ProductEditor.vue:3-10`
  Erledigt: Alle Schriften werden lokal via `@fontsource*` ausgeliefert, keine externen Google-Font-Links oder Tracker mehr. *(Einwilligungsfreiheit hängt zusätzlich am neuen Punkt A17 zum `voenix.guest`-Cookie!)*
- [ ] **A7. Preisangaben ohne MwSt.- und Versandhinweis** – `frontend/src/components/shop/ProductPrice.vue:64-99`, `frontend/src/components/shop/CartSummary.vue:62-65`, `frontend/src/views/shop/CheckoutView.vue:452-455`, `frontend/src/components/shop/OrderDetails.vue:160-163`
  Produktkarten, Assistent, Warenkorb und Kasse zeigen Preise ohne `inkl. MwSt.` und ohne `zzgl. Versand`-Link auf `/shipping` (§§ 1, 3, 6 PAngV).
  Fix: Hinweis an Preis und Gesamtsumme, `zzgl. Versand` mit Link auf `/shipping`.
- [ ] **A8. Kasse: wesentliche Merkmale und Lieferzeit fehlen** – `frontend/src/views/shop/CheckoutView.vue:410-426`
  Bestellübersicht direkt über dem Bestellbutton (§ 312j Abs. 2 BGB) zeigt nur `articleName` und `variantName × quantity`.
  Fix: Produktart mit Kernangaben (Material/Füllmenge, „personalisiert“), Motiv-Vorschau (`item.imageId`), Lieferzeit und `inkl. MwSt.` ergänzen.
- [x] **A9. Versandkosten widersprechen sich** – `backend/src/modules/cart/totals.ts:5-8`, `frontend/src/i18n/locales/de.json:398-408`, `en.json:398-408`, `frontend/src/components/shop/CartSummary.vue:29`
  Erledigt: Seite und Berechnung zeigen übereinstimmend `4,90 €`, frei ab `50 €`. *(Hinweis: bei Wechsel auf DE-only — siehe D3 — Versandseite und `/api/countries` mitanpassen.)*
- [ ] **A10. Fest einprogrammierte Kundenstimmen** – `frontend/src/views/shop/HomeView.vue:328-370`, `frontend/src/components/shop/campaigns/royal-dog/RoyalDogReviews.vue:9-45`, `de.json:508-531,637-674`, `en.json`
  Markus T., Sarah K., Julia M. (Startseite) und Lena M., Tom B., Anna K. (`/royal-dog`) sind weiter live (§ 5, § 5b Abs. 3 UWG). Auch ungenutzte Fake-Stats (`4,9 Sterne`, `50k+ Designs`) aus den Locales entfernen.
  Fix: beide Bewertungs-Abschnitte entfernen, bis echte Bewertungen vorliegen.
- [ ] **A11. Werbeaussagen ohne Deckung** *(teilweise erledigt)* – `de.json:391-406,541-611,768`, `en.json`, `frontend/src/views/shop/HomeView.vue:139-159,230,276`, `frontend/src/components/shop/Footer.vue:45-52,137`
  Erledigt: falsche „ab“-Preise und pauschale „Bio-Baumwolle / Fair Trade“-Aussagen wurden entfernt.
  Noch offen:
  - „Made in Germany“ (`de.json:611`) und „Wir drucken und versenden per DHL aus Deutschland“ (`de.json:591`, `Footer.vue:137`) für SPOD-Artikel prüfen oder streichen.
  - Nicht im Sortiment befindliche „Poster“ (`de.json:541,591`) und „Als Karte“ (`HomeView.vue:139-159`, `de.json:569`) entfernen.
  - Alle gezeigten Zahlarten (PayPal, Klarna, Apple Pay, Google Pay, Sofortüberweisung in `Footer.vue:45-52` & `de.json:768`) müssen im Mollie-Live-Profil wirklich aktiv sein.
  - „Versand in 3–5 Tagen“ mit AGB und `/shipping` abgleichen.
- [ ] **A12. Stilnamen mit fremden Rechten** – Admin → Prompts (`backend/src/modules/prompt/schema.ts:56`)
  „Hundertwasser“, „Picasso“, „Funky Pop Toy Style“, „Instagramm Beauty“ im Admin umbenennen.
- [ ] **A13. Herstellerangaben fehlen (EU-GPSR)** – `backend/src/modules/article/schema.ts:50-119`, `storefront.ts:25-70`, `ProductCard.vue`, `SelectArticleStep.vue`
  Art. 19 EU-GPSR verlangt im Angebot Name, Anschrift und E-Mail des Herstellers (+ ggf. Sicherheits-/Warnhinweise). Fehlt in DB-Schema, API und UI.
  Fix: Herstellerangaben pro Artikel erfassen und in der Shop-UI anzeigen.
- [ ] **A14. Foto-Upload ohne Bildrechte-/KI-Hinweis** – `frontend/src/components/shop/wizard/steps/UploadImageStep.vue:44-75`, `frontend/src/components/shop/editor/ProductDraftUpload.vue:44-70`
  Beide Upload-Stellen (Wizard **und** Editor-Direktupload) haben keinen Hinweis zu Bildrechten, abgebildeten Personen (§§ 22/23 KUG) und KI-Verarbeitung (fal.ai) mit Link auf `/privacy` und `/terms`.

### Neu entdeckte rechtliche Punkte (Audit 2026-11-22)

- [x] **A15. [BLOCKER] Englischer Bestellbutton rechtlich unwirksam (§ 312j Abs. 3 & 4 BGB)** – `frontend/src/i18n/locales/en.json`
  Erledigt: `checkout.submit` in `en.json` auf `"Order with obligation to pay"` geändert.
- [ ] **A16. [BLOCKER] Zahlungsinformationen fehlen vor/im Bestellprozess (§ 312j Abs. 1 BGB)** – `frontend/src/views/shop/PaymentView.vue`, `frontend/src/views/shop/CheckoutView.vue:370-380`
  `/payment` ist Platzhalter und die Kasse nennt die akzeptierten Zahlarten nicht.
  Fix: `/payment` mit den real aktiven Mollie-Zahlarten füllen und Zahlarten im Checkout sichtbar benennen.
- [x] **A17. [HOCH] 30-Tage-Cookie `voenix.guest` wird bei jedem passiven Seitenaufruf gesetzt (§ 25 TDDDG)** – `backend/src/modules/generator/routes.ts`, `backend/src/modules/generator/magic-coins.ts`
  Erledigt: `GET /api/magic-coins/balance` nutzt `tryMagicCoinsOwner` (`guestTokens.tryGet(c)`) und `coins.readBalance(owner)`, liefert ohne Cookie `10` ohne `Set-Cookie` und ohne DB-Zeile; erst `POST /api/generator/generate` legt Gast-Cookie und DB-Zeile an.
- [ ] **A18. [HOCH] Art. 13 DSGVO-Hinweise bei Kasse & Registrierung fehlen** – `frontend/src/views/shop/CheckoutView.vue:458-473`, `frontend/src/views/auth/RegisterView.vue:85-142`
  Weder Kasse noch Registrierung enthalten einen Datenschutz-Link zu `/privacy`.
  Fix: Hinweis mit Link zu `/privacy` (ohne unnötige Pflicht-Checkbox) direkt über dem Submit-Button ergänzen.
- [ ] **A19. [HOCH] Streichpreise ohne 30-Tage-Tiefstpreis (§ 11 PAngV)** – `frontend/src/components/shop/ProductPrice.vue:73-97`, `backend/src/modules/pricing/schema.ts:39-40`
  Bei Rabatten werden Streichpreis und `-X%` ohne 30-Tage-Referenzpreis angezeigt.
  Fix: Zum Go-live keine Artikel-/Prompt-Rabatte aktivieren, bis 30-Tage-Tiefstpreis-Tracking implementiert ist.
- [ ] **A20. [MITTEL] Impressum & Datenschutz auf `/wizard` und `/editor` ausgeblendet (§ 5 DDG)** – `frontend/src/router/shop.ts:136,145`, `frontend/src/layouts/ShopLayout.vue:20,36`
  `hideFooter: true` entfernt alle Pflichtlinks; Besucher, die direkt auf `/wizard` landen, finden kein Impressum/Datenschutz.
  Fix: Schmale Legal-Leiste (`Impressum · Datenschutz · AGB`) im Wizard/Editor einblenden.
- [ ] **A21. [MITTEL] Versandseite ohne Lieferzeiten & hardcodierte Länderliste** – `frontend/src/views/shop/ShippingView.vue`, `de.json:368-426`, `en.json:368-426`
  Keine konkreten Lieferzeiten genannt; 8 Länder statisch im Text statt dynamisch aus `/api/countries`. Auch Angabe „ab 150 € Umsatz“ (`de.json:413`) prüfen.

---

## B. Security-Blocker & Missbrauchsschutz

### Offene Punkte aus `TODO.md` & Vor-Audit (Stand 2026-11-22 verifiziert)

*(Bereits erledigt: `TODO #1` Route-Pattern-Logging — alte Webhook-Secrets trotzdem rotieren!; `TODO #2` `Secure`-Cookies; `TODO #6` Bildgrößen-Allowlist; `sourcemap: false`.)*

- [x] **B1. [BLOCKER] Service Worker cacht private `/api/`-Daten über Logout hinaus (`TODO #7`, auch DSGVO Art. 32)** – `frontend/vite.config.ts`
  Erledigt: `/api/`-Eintrag aus `workbox.runtimeCaching` entfernt; der Service Worker cacht nur noch `/images/`.
- [x] **B2. [BLOCKER] Mengenerhöhung zum alten / 0-€-Aktionspreis (`TODO #4`)** – `backend/src/modules/cart/service.ts`, `backend/src/modules/cart/repository.ts`, `backend/src/modules/cart/routes.ts`
  Erledigt: `PATCH /api/cart/items/:itemId` prüft `purchasable`/Preis (bzw. Prompt-Nutzbarkeit), lehnt nicht kaufbare Zeilen mit `409 CART_ITEM_UNAVAILABLE` ab und aktualisiert `price_cents` und `prompt_price_cents` aus dem Katalog; Doku in `docs/dev/backend/packages/cart-package.md` angepasst.
- [x] **B3. [BLOCKER] Einmal-Gutschein mehrfach einlösbar über „erneut bezahlen“ (`TODO #5`)** – `backend/src/modules/order/payment/service.ts`, `backend/src/modules/promotion/codes.ts`, `backend/src/modules/checkout/service.ts`
  Erledigt: `startPayment` prüft unter Order-Lock, ob eine aktive Promotion-Reservierung existiert, reserviert andernfalls vor dem Mollie-Call neu und lehnt fehlgeschlagene Reservierungen vor Mollie mit `promotionRejected` (`409` bei Limit) ab; Doku in `docs/dev/backend/packages/checkout-package.md` und `order-package.md` angepasst.
- [x] **B4. [BLOCKER] Deaktivierte Artikel bleiben bestellbar (`TODO #3`)** – `backend/src/modules/order/service.ts`, `backend/src/modules/checkout/service.ts`, `backend/src/modules/cart/service.ts`
  Erledigt: Placement prüft `purchasable === true` und `grossSalesPriceCents !== null` (`409 CART_ITEM_UNAVAILABLE`), und der Checkout liest den Warenkorb-Snapshot über `lockActiveCart` erst nach dem Cart-Lock.
- [x] **B5. [BLOCKER] Kein Rate-Limit auf Auth- & Gutschein-Endpunkten (`TODO #8`)** – `backend/src/modules/account/routes.ts`, `backend/src/modules/cart/routes.ts`, `backend/src/composition.ts`
  Erledigt: Getrennte `createClientIpRateLimiter`-Instanzen für `POST /api/auth/login` (20/15 min), `register`/`forgot-password`/`resend-confirmation` (je 5/h) und `POST /api/cart/promotion` (20/15 min, nach CSRF); liefern `429` + `Retry-After` im einheitlichen `ApiError`-Format.
- [x] **B6. [BLOCKER] Keine HTTP-Sicherheits-Header** – `backend/src/platform/http/app.ts`, `backend/src/composition.ts`
  Erledigt: Hono `secureHeaders()` aktiv (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, HSTS & `upgrade-insecure-requests` bei HTTPS/`secureCookies`, CSP mit `frame-ancestors 'none'`, `object-src 'none'`, `data:`/`blob:` für Editor), plus `Referrer-Policy: no-referrer` auf Token-Routen (`/order/*`, `/confirm-email`, `/reset-password`, `/confirm-change-email`, `/set-password`, `/api/order-lookup/*`).
- [x] **B7. [HOCH] Kontoübernahme vor Registrierung & unvollständiger Reset (`TODO #9`)** – `backend/src/modules/account/service.ts`, `backend/src/modules/account/repository.ts`
  Erledigt: `register` überschreibt bestehende unbestätigte Registrierungen (Passwort-Hash, Lockout-Felder, löscht alle alten Tokens und sendet neuen Bestätigungslink); `resetPassword` setzt zusätzlich `email_confirmed = true`, `failed_login_count = 0`, `locked_until = null`.

### Neu entdeckte Security-Punkte (Audit 2026-11-22)

- [x] **B8. [BLOCKER] `fal.ai`-Kosten-Missbrauch: Magic-Coins TOCTOU-Race + kein globales Limit (`TODO #10`)** – `backend/src/modules/generator/service.ts`, `backend/src/modules/generator/magic-coins.ts`, `backend/src/platform/ratelimit/client-ip-rate-limiter.ts`, `backend/src/platform/settings.ts`, `backend/src/composition.ts`
  Erledigt: `coins.spend` bucht 1 Coin atomar vor dem `fal.ai`-Call ab (`WHERE balance >= 1 RETURNING balance`), bei `upstreamFailure` oder Fehler wird atomar erstattet (`+1`), plus globales Stundenlimit `GENERATOR_GLOBAL_MAX_PER_HOUR` (Default `200`).
- [x] **B9. [HOCH] `/api/images/private/*` nutzt `requireAuthenticated` statt `requireAdmin`** – `backend/src/modules/image/routes.ts`
  Erledigt: Auf `requireAdmin` umgestellt und im Test (`401` anonym, `403` Kunde, `200` Admin) abgesichert.
- [x] **B10. [MITTEL] Ungeprüfte Uploads an `fal.ai` & ungeprüfter Ergebnis-Download** – `backend/src/modules/image/codec.ts`, `backend/src/modules/generator/upload.ts`, `backend/src/modules/generator/routes.ts`, `backend/src/modules/generator/fal.ts`
  Erledigt: Uploads (`/api/generator/generate` & `/api/admin/images/background-removal`) werden vor dem `fal.ai`-Call mit `sharp` (`validateImageUpload`, ≤ 40 MP, Magic-Bytes + Dekodierung) geprüft; Ergebnis-Download prüft manuell bis 5 Redirects (nur externe `https:`-Ziele ohne interne/Loopback-/Private-IPs) und validiert die Bytes per `inspectImageBytes`.
- [x] **B11. [MITTEL] Gefährliche Produktiv-Defaults (`RATE_LIMIT_TRUST_FORWARDED_FOR` & `DATABASE_SSL_MODE`)** – `backend/src/platform/settings.ts`, `backend/src/main.ts`, `backend/.env.example`
  Erledigt: Beim Start wird eine secret-freie Konfigurationszusammenfassung (inkl. Mollie-Modus `live`/`test`) geloggt sowie explizite `WARN`-Logs bei `HTTPS`-`FRONTEND_BASE_URL` mit `DATABASE_SSL_MODE=disable`, `EMAIL_ENABLED=false`, `GENERATOR_DUMMY_MODE=true` oder `RATE_LIMIT_TRUST_FORWARDED_FOR=false`. *(Die konkreten Produktiv-Env-Variablen werden beim Deploy in E2 gesetzt.)*
- [x] **B12. [MITTEL] `magic-wormhole` (+ Python) im Produktions-Docker-Image** – `Dockerfile`
  Erledigt: Python-`magic-wormhole` (`apt-get`) entfernt und durch das eigenständige Rust-Binary `magic-wormhole.rs` (`wormhole-rs` `0.8.1`, SHA-256-geprüft, Symlink `wormhole`, ohne Python-Runtime im Image) ersetzt, da das Tool weiter für Server-zu-Server-Datentransfers benötigt wird.
- [ ] **B13. [MITTEL] Klartext-Secrets in PostgreSQL & Admin-Session-Revocation (`TODO #11`)** – `backend/src/modules/production/repository.ts:204-244`, `backend/src/platform/auth/auth.ts:91-116,215-220`
  SFTP-Passwörter und SPOD-Live-Tokens liegen unverschlüsselt in der DB; `requireAdmin` prüft Rollen nur aus dem Stateless-Cookie (nicht widerrufbar bei Logout/Passwortwechsel/Rollenentzug).
  Fix: SFTP-/SPOD-Secrets mit AES-256-GCM at-rest verschlüsseln; `requireAdmin` gegen DB prüfen (wie `requireSupplier`).
- [x] **B14. [NIEDRIG] Unvalidierter `redirect`-Parameter & `bun audit`** – `frontend/src/views/auth/LoginView.vue`
  Erledigt: `route.query.redirect` wird auf einzelne relative Pfade (`^/(?!/)`) geprüft; `bun audit` in `frontend/` (29 Prod-Findings / 114 inkl. Dev-Deps) und `backend/` (0 Prod-Findings, 5 Dev-only-Findings in `testcontainers`/`eslint`) ausgeführt.

---

## C. Funktionale & Technische Blocker

### Frontend (bestehend + neu)

- [ ] **C1. [BLOCKER] Platzhalterseiten im Footer** – `AboutView.vue`, `FaqView.vue`, `ContactView.vue`, `SupportView.vue`, `PaymentView.vue`, `TermsView.vue`, `ReturnsView.vue`, `frontend/src/components/shop/Footer.vue:29-43`
  7 von 10 Footer-Links zeigen „Hier findest du bald …“.
  Fix: Rechtstexte & `/payment` füllen, unfertige Links aus `Footer.vue` entfernen.
- [ ] **C2. [BLOCKER] Stil-Aufpreis wird angezeigt, aber nie berechnet** – `SelectStyleStep.vue:238-243`, `WizardView.vue:209-221`, `stores/shop/editor.ts:35-48`, `ProductEditor.vue:501-508`
  Assistent zeigt Stil-Preis (z. B. `3,95 €`), `promptId` geht aber zwischen Wizard → `EditorDraft` → `addToCart` verloren (`promptPrice` immer `0`).
  Fix: `promptId` durchreichen oder Stil-Preisanzeige entfernen.
- [x] **C3. [BLOCKER] Magic Coins halb fertig** – `Header.vue`, `MobileMenu.vue`, `MagicCoinsView.vue`, `GenerateStep.vue`
  Erledigt: Header/MobileMenu zeigen nur den aktuellen Coin-Stand ohne Kauf-Link, Refill-CTAs in `GenerateStep` entfernt und Kaufpakete/Preise auf `/magic-coins` entfernt, bis ein echter Checkout existiert.
- [ ] **C4. [HOCH] `/royal-dog` ohne Prompt-ID & fehlerhaftes Layout** – `frontend/src/views/shop/RoyalDogView.vue:13`, `frontend/src/layouts/ShopLayout.vue:13`
  `ROYAL_DOG_PROMPT_ID` ist `null`; außerdem rendert `ShopLayout` nur `home` full-bleed, sodass `/royal-dog` in `max-w-7xl px-4` gequetscht wird.
  Fix: Prompt anlegen und ID setzen + `campaignLanding` full-bleed unterstützen (oder Seite bis dahin nicht bewerben/routen).
- [x] **C5. [HOCH / NEU] Abgebrochene/fehlgeschlagene Mollie-Zahlung zeigt „Warten auf Zahlungsbestätigung“** – `frontend/src/views/shop/OrderConfirmationView.vue`
  Erledigt: `hasTerminalPayment` (`FAILED`, `CANCELED`, `EXPIRED`) zeigt Fehler-Icon, passenden Titel/Text und prominenten „Erneut bezahlen“-Button; `?orderId=` strikt mit `/^[1-9]\d*$/` validiert; Doppel-Submit via `isRedirecting` gesperrt.
- [x] **C6. [HOCH / NEU] Mobile-Editor versteckt „In den Warenkorb“-Text auf Smartphones (`<= 430px`)** – `frontend/src/components/shop/editor/ProductEditor.vue`
  Erledigt: `max-[430px]:sr-only` und Icon-Only-Schrumpfung entfernt, sodass der Button-Text auf allen mobilen Breiten sichtbar bleibt.
- [x] **C7. [HOCH / NEU] GIF im Upload erlaubt, vom KI-Generator aber abgelehnt (400)** – `UploadImageStep.vue`, `stores/shop/wizard.ts`, `ImageUploadDropzone.vue`, `ProductDraftUpload.vue`, `de.json`, `en.json`
  Erledigt: `image/gif` aus akzeptierten Typen und Hilfetexten für den Shop-Upload entfernt.
- [x] **C8. [HOCH] Wizard behält alte Motive & Warenkorb-Ladefehler wirkt wie „leer“ (`TODO #12, #13`)** – `stores/shop/imageGeneration.ts`, `WizardView.vue`, `CartView.vue`, `CheckoutView.vue`
  Erledigt: Generationszähler + `AbortController` in `imageGeneration.ts`, `reset()` bei Artikel-/Foto-/Stilwechsel in `WizardView.vue`; Fehlerkarte + Retry in `CartView.vue` und `CheckoutView.vue` statt Falsch-„leer“ bzw. Redirect.
- [x] **C9. [HOCH / NEU] Concurrent-Fetch-Bug in `prompts`-, `catalog`- & `articleCategories`-Stores** – `stores/shop/prompts.ts`, `catalog.ts`, `articleCategories.ts`
  Erledigt: Laufendes `pendingRequest`-Promise wird in allen drei Stores gespeichert und an parallele Aufrufer zurückgegeben.
- [x] **C10. [HOCH] Admin-Bundle für alle & fehlende Kompression (`TODO #17`)** – `frontend/vite.config.ts`, `backend/src/platform/http/app.ts`
  Erledigt: Chunk-Gruppen `admin`, `shop`, `shared` und `radix-vue` aus `vite.config.ts` entfernt (Admin-Views lazy-loaded); Hono `compress()` in `app.ts` aktiv (komprimiert JSON/Text/JS/CSS, lässt Binärbilder und kleine Responses < 1 KB mit `Content-Length` unberührt).
- [x] **C11. [MITTEL] `sitemap.xml` fehlt & SW fängt `/robots.txt` ab** – `frontend/public/robots.txt`, `frontend/public/sitemap.xml`, `frontend/vite.config.ts`
  Erledigt: `sitemap.xml` angelegt, in `robots.txt` referenziert und `/^\/robots\.txt$/`, `/^\/sitemap\.xml$/` in `navigateFallbackDenylist` aufgenommen.
- [x] **C12. [MITTEL] UX- & i18n-Feinschliff (`TODO #18–#20` + NEU)** – `CartLineItem.vue`, `CartSummary.vue`, `CartView.vue`, `CheckoutView.vue`, `OrderConfirmationView.vue`, `RegisterView.vue`, `LoginView.vue`, `ProfileView.vue`, `OrderView.vue`, `OrderDetails.vue`, `stores/shared/{auth,locale,theme}.ts`, `i18n/index.ts`, `LandingCarousel.vue`, `HeaderSubcategoryMenuCard.vue`, `MobileMenu.vue`, `TextToolPanel.vue`, `CartButton.vue`, `LanguageDropdown.vue`, `frontend/package.json`
  Erledigt: Doppelklick-/Doppel-Submit-Schutz im Warenkorb und Checkout, „Zur Kasse“-Sperre bei nicht verfügbaren Artikeln, `minlength="8"` bei Registrierung, lokalisierte Fehler-Fallbacks statt roher Backend-Texte, `auth.ts`-Logout nur bei `401`, `try/catch` für `localStorage`, `LandingCarousel` `ResizeObserver` via `watch(trackRef)`, ungenutzte Deps (`@iconify/vue`, `@tanstack/vue-table`, `radix-vue`, `vite-plugin-vue-devtools`) und `useImageContainRect.ts` entfernt, i18n-/A11y-Reste behoben.

### Backend, Datenbank & Fulfillment (bestehend + neu)

- [x] **C13. [HOCH / NEU] Payment-Retry storniert Bestellung dauerhaft bei kurzem Mollie-Fehler** – `backend/src/modules/order/payment/service.ts`, `backend/src/modules/checkout/service.ts`
  Erledigt: `startPayment` akzeptiert `{ cancelOnFailure }` (`true` beim Erst-Checkout, `false` beim Retry); bei Mollie-Fehler im Retry bleibt die Bestellung `PENDING` und eine frisch gezogene Promotion-Reservierung wird sauber freigegeben.
- [x] **C14. [HOCH / NEU] `DATABASE_MAXIMUM_POOL_SIZE` default `100` droht Postgres zu erschöpfen** – `backend/src/platform/settings.ts`, `backend/.env.example`
  Erledigt: Default auf `20` gesenkt (Minimum `1`).
- [x] **C15. [HOCH / NEU] Kein `/api/health` / `/api/ready` Endpoint & kein `Dockerfile HEALTHCHECK`** – `backend/src/platform/http/health.ts`, `backend/src/composition.ts`, `Dockerfile`, `backend/Dockerfile`
  Erledigt: `GET /api/health` (`200 { status: "ok" }`) und `GET /api/ready` (`SELECT 1` mit 2 s Timeout, `200`/`503`) vor `serveFrontend` gemountet + `HEALTHCHECK` gegen `/api/ready` in beiden Dockerfiles ergänzt.
- [x] **C16. [HOCH / NEU] Graceful Shutdown ohne Timeout, Re-entrancy-Guard & `spodCatalogSync`-Drain** – `backend/src/main.ts`, `backend/src/platform/shutdown.ts`, `backend/src/modules/article/spod-article/sync.ts`, `backend/src/modules/spod/client.ts`, `backend/src/composition.ts`
  Erledigt: Re-entrancy-Guard in `shutdown()`, 12 s Drain-Timeout vor `server.stop(true)`, sowie `AbortController`/Drain-Handle in `spodCatalogSync.stop()` vor `db.destroy()`.
- [ ] **C17. [HOCH] SPOD-Webhook-Fallback & Lieferanten-PDF-Snapshot (`TODO #14, #15`)** – `backend/src/modules/production/fulfillment/service.ts:206-218`, `backend/src/modules/production/delivery/generation.ts:63-68`, `pdf/renderer.ts:83`
  Webhook-Fallback nur zulassen, wenn `externalReference === null`; Lieferanten-PDF gegen `production_job_items` prüfen (`ITEM_SET_CHANGED` wie bei SPOD).
- [x] **C18. [MITTEL / NEU] DB-Constraint `ck_orders_owner` verhindert DSGVO-Kontolöschung** – `backend/migrations/V40__go_live_db_hardening.sql`, `backend/src/modules/order/placement.test.ts`
  Erledigt: `V40__go_live_db_hardening.sql` entfernt `ck_orders_owner`; Test verifiziert, dass registrierte Nutzer mit Bestellungen gelöscht werden können und die Bestellung mit `user_id = NULL` erhalten bleibt.
- [ ] **C19. [MITTEL / NEU] Endlos-Retry bei permanenten Fehlern (Email `4xx` & SPOD `ORDER_CREATE_REJECTED`)** – `backend/src/modules/email/worker.ts:39-64`, `backend/src/modules/production/spod/repository.ts:103-129`
  Sweego-`4xx` und abgelehnte SPOD-Orders laufen ewig im Intervall weiter (SPOD alle 60 s gegen 60 req/min Rate-Limit; `EMAIL_STALLED` bleibt permanent offen).
  Fix: Exponential Backoff + Max-Retries / Dead-Letter bzw. Admin-Retry/Dismiss einführen (und `email-package.md` / `spod-fulfillment.md` aktualisieren).
- [ ] **C20. [MITTEL / NEU] Plattenwachstum (`/app/data`), `email_jobs`-Index & 5-Minuten-Mail-Verzögerung** *(teilweise erledigt)* – `backend/migrations/V40__go_live_db_hardening.sql`, `backend/src/platform/settings.ts`, `backend/src/modules/image/orphans.ts`, `production/pdf/artifact-store.ts`
  Erledigt: `EMAIL_POLL_INTERVAL_MINUTES`-Default auf `1` gesenkt und Partial Index `ix_email_jobs_open` (`WHERE sent_at IS NULL`) in `V40` ergänzt.
  Noch offen: Retention für alte `email_jobs`-Payloads sowie Ablauf/Cleanup für abgebrochene `ACTIVE`-Warenkörbe und alte Produktions-PDFs.
- [x] **C21. [MITTEL] Gefährliches Migrations-Skript & Backend-Kleinkram (`TODO #16, #18`)** – `scripts/migrate-legacy-data.sh`
  Erledigt: Skript bricht bei nicht-lokalem Ziel-Host (`!= localhost/127.0.0.1/::1`) ab, sofern nicht explizit `--yes-wipe-target` übergeben wird.

### Katalog & Datenqualität

- [ ] **C22. Testdaten im Katalog bereinigen** – Admin
  - Tassen-Langbeschreibungen (`.`, `asf`, `qwef`, `sdg`) ersetzen.
  - Tippfehler in Stilnamen beheben (`Stylizet Anime`, `Expressive Oil Paining`, `Instagramm Beauty`).
  - Saisonale Kategorie „Valentinstag“ (7,08 €) und „Western Comic“ (0,00 €) prüfen/deaktivieren.
  - Englische Stil-/Kategorienamen in deutschem Shop vereinheitlichen; Marge, MwSt. und Lieferanten-Zuordnung je aktivem Artikel prüfen.

---

## D. Geschäftliche & Betriebs-Lücken (NEU — vor Launch klären)

- [ ] **D1. [HOCH] Rechnungsstellung, MwSt.-Snapshot & Buchhaltung (§ 14 UStG / GoBD)** – `backend/src/modules/order/schema.ts:13-72`, `backend/src/modules/order/repository.ts:481-551`
  `orders` und `order_items` speichern nur Brutto-Cents — kein MwSt.-Satz, keine Netto-/Steuer-Aufteilung, keine fortlaufende Rechnungsnummer, kein PDF-/DATEV-/Lexoffice-Export.
  Klären: Wie werden Rechnungen (insb. bei Kunden-/B2B-Anfragen) und Buchhaltung zum Launch abgewickelt (z. B. Mollie → Lexoffice/sevDesk + manuelles PDF auf Anfrage, oder MwSt.-Snapshot im Backend ergänzen)?
- [ ] **D2. [HOCH] Refund-, Chargeback- & Storno-Workflow (+ Zahlungsbestätigung)** – `backend/src/modules/order/payment/mollie.ts:40-46`, `order/payment/service.ts:125-156`, `frontend/src/views/admin/OrderTimelineView.vue:79-100`
  - Keine Mollie-Refunds/Chargebacks im Backend (Webhooks auf bereits bezahlte Orders werden ignoriert; `amountRefunded`/`amountChargedBack` ungeprüft → Chargeback bleibt unbemerkt!).
  - Keine Storno-Funktion für bezahlte Orders; von SPOD stornierte Jobs lassen das Admin-„Unshipped“-Badge & `SPOD_REMOTE_ATTENTION` dauerhaft offen.
  - `OrderConfirmation` geht bei `PENDING` raus; bei `PAID` erhält der Kunde keine separate Zahlungseingangs-Mail (erst wieder bei Versand).
  Fix: Mindestens `amountRefunded`/`amountChargedBack` im Mollie-Webhook erfassen + Ops-Alert bei Chargebacks, sowie klares Support-SOP für manuelle Refunds in Mollie.
- [ ] **D3. [HOCH] EU-Versand-Falle (Marge, EPR-Verpackungsgesetze & OSS)** – `backend/src/modules/cart/totals.ts:5-8`, `de.json:375-410`, Admin → Länder
  Aktuell sind 7 EU-Länder (AT, BE, ES, FR, IT, NL, SE) für pauschal `4,90 €` (frei ab `50 €`) aktiv — bei realen DHL-EU-Kosten (~14–17 €) stark defizitär **und** ab Paket 1 ausländische Verpackungspflichten (AT VerpackVO mit Bevollmächtigtem, FR CITEO/Triman etc. ohne Bagatellgrenze!). Zudem prüft Checkout nicht gegen SPOD-Zielländer.
  **Empfehlung für Go-live:** Im Admin vorerst **nur Deutschland** aktivieren und `ShippingView.vue` / `de.json` / `en.json` entsprechend anpassen.
- [ ] **D4. [HOCH] Monitoring, Error-Tracking, Backups & Single-Instance-Betrieb** – Sliplane / Infrastruktur
  - Kein Sentry/GlitchTip und kein externes Uptime-Monitoring (fällt Sweego aus, gehen auch `OPERATIONS_ALERT`-Mails nicht raus!).
  - Automatisierte tägliche Postgres-Backups (+ 1× Restore-Test!) und Backup-Konzept für persistentes Volume **`/app/data`** (Katalogbilder, Kunden-Printdaten, PDFs) einrichten.
  - Sliplane zwingend auf **genau 1 Instanz (Single Replica)** begrenzen (lokales `/app/data`, In-Memory-Rate-Limiter und nicht-idempotenter SPOD-Worker — 2 Instanzen können doppelte bezahlte SPOD-Orders erzeugen!).
  - `/admin/access-tokens` (für `/api/mcp`) vor Launch auf alte Dev-Tokens prüfen.

---

## E. Domain-Umzug, Produktiv-Konfiguration & Launch-Tag

- [ ] **E1. 301-Weiterleitungen für alte WordPress-URLs** *(Backend-Redirects erledigt)* – `backend/src/platform/http/legacy-redirects.ts`, `backend/src/composition.ts`
  Erledigt (Code): Serverseitige 301-Redirects vor `serveFrontend` für `/impressum`, `/widerruf`, `/versand`, `/zahlung`, `/faq/`, `/ueber-uns`, `/tassenuebersicht`, `/firmenkunden`, `/pdf/AGBs.pdf` (mit/ohne Trailing-Slash) inkl. Unit- & Integrationstests.
  Noch offen (Launch-Tag/Infra): `www.voenix.shop` ↔ `voenix.shop` kanonisch weiterleiten; Testinstanz `voenix-shop-test-ts.sliplane.app` abschalten, per Auth sperren oder mit `noindex` versehen.
- [ ] **E2. Produktiv-Konfiguration & Secret-Rotation** – `backend/src/platform/settings.ts`
  Alle Produktiv-Variablen explizit setzen (nicht auf Defaults verlassen!):
  - `FRONTEND_BASE_URL=https://voenix.shop` (Default ist `http://localhost:5173` → schaltet sonst still `Secure`-Cookies aus!)
  - `MOLLIE_API_KEY=live_...` & `MOLLIE_WEBHOOK_URL=https://voenix.shop/api/payments/webhook/<NEUES_SECRET>`
  - **Frische/rotierte Secrets**: `AUTH_SESSION_SECRET` (≥32 Bytes), `MOLLIE_WEBHOOK_SECRET` (rotieren wegen altem `TODO #1`!), `PRODUCTION_SPOD_WEBHOOK_SECRET` (rotieren!)
  - `EMAIL_ENABLED=true`, `EMAIL_API_KEY=<live>`, `EMAIL_FROM_EMAIL=<verifiziert>`, `EMAIL_POLL_INTERVAL_MINUTES=1`
  - `GENERATOR_DUMMY_MODE=false`, `GENERATOR_API_KEY=<live fal.ai>`
  - `RATE_LIMIT_TRUST_FORWARDED_FOR=true` (nach Proxy-Hop-Check auf Sliplane)
  - `DATABASE_SSL_MODE=require` (bzw. `verify-full`), `DATABASE_MAXIMUM_POOL_SIZE=20`
  - `OPERATIONS_ALERT_EMAIL=<überwachtes Ops-Postfach>`
  - Persistentes Volume auf `/app/data` gemountet & für Container-User `app` beschreibbar.
  - **In Admin UI (`/admin/logistics/destinations`)**: SPOD von `STAGING` auf `PRODUCTION` + Live-Token umstellen, Katalog neu synchronisieren, Live-Webhooks registrieren (`Shipment.sent`, `Order.cancelled`, `Order.needs-action`); Tassen-SFTP-Zugang + Host-Key-Fingerprint verifizieren.
- [ ] **E3. Mail-Zustellbarkeit (Sweego)**
  SPF von `voenix.shop` um Sweego ergänzen, DKIM & Return-Path CNAME einrichten, DMARC prüfen (`p=none` perspektivisch schärfen), Zustelltest an Gmail, GMX, Web.de und Outlook.de (Inbox statt Spam). Hinweis: Sweego-Bounces sind nicht im Shop sichtbar → regelmäßig im Sweego-Dashboard prüfen.
- [ ] **E4. Echte End-to-End-Testbestellungen & Rollback-Plan**
  - Vor Traffic: DB-Snapshot erstellen; altes WordPress 48–72 h read-only als DNS-Rollback belassen (Achtung: DB-Migrationen sind forward-only).
  - Live-Tests auf echten Geräten (iOS Safari + Android Chrome) mit Live-Mollie-Key:
    1. **Tasse (SFTP)**: Foto → KI-Generierung → Editor → Warenkorb (mit/ohne Gutschein) → Live-Zahlung → Bestellmail in Inbox → `/order/:token`-Link → PDF auf SFTP + Lieferanten-Mail → Versandmarkierung → Versandmail + Tracking.
    2. **Textil (SPOD)**: Gleicher Ablauf → Telefon-Validierung → Live-Order in Spreadconnect inkl. Motiv/Hotspot (insb. falls `OVERALL_FRONT`-Artikel aktiv sind).
    3. **Konto & Fehlerpfade**: Registrierung → E-Mail-Bestätigung → Passwort-Reset → abgebrochene Mollie-Zahlung → Retry-Zahlung.
- [ ] **E5. Erste 48 Stunden nach Go-live**
  `/admin/operations` (`lastScanAt <= 5 min`), Sliplane-Logs (`ERROR`/`5xx`/`429`), Mollie-Webhook-Logs, Sweego-Bounces, `/app/data`-Speicher, Postgres-Connections und fal.ai-Verbrauch aktiv prüfen; `sitemap.xml` in Google Search Console einreichen.

---

## F. Außerhalb der Seite klären

- [ ] **F1. Auftragsverarbeitungsverträge (AVV / Art. 28 DSGVO)** mit Sliplane, Sweego, **fal.ai (US-Drittlandtransfer, SCCs/DPF, Zero-Training/Retention für Kundenfotos prüfen!)**, SPOD/Spreadshirt, Tassen-Lieferant und iubenda; Verarbeitungsverzeichnis (Art. 30 DSGVO) & TOMs (Art. 32 DSGVO) dokumentieren.
- [ ] **F2. Verpackungsregistrierung (LUCID / VerpackG)** + Duales System vor dem ersten deutschen Paket klären (inkl. Rollenverteilung mit SPOD und Tassen-Lieferant).
- [ ] **F3. EU-Versand (OSS & ausländisches EPR)** klären — oder zum Go-live auf Deutschland beschränken (siehe D3).
- [ ] **F4. Kosten- & Konto-Schutznetze**: Hard-Spend-Limit + Low-Credit-Alert bei **fal.ai** setzen; **Mollie Live-KYC** & alle aktivierten Zahlarten abgleichen; **Support-Postfach** (`write-us@voenix.shop`) + Telefonnummer besetzen; kurzes internes **Support-SOP** (manuelle Refunds, Reklamation/Neudruck, Kundenrechnungen, `SPOD_QUARANTINED`) festhalten; **BFSG-Ausnahme** für Kleinstunternehmen (<10 MA & ≤2 Mio. € Umsatz, § 3 Abs. 3 BFSG) intern dokumentieren.

---

## Geprüft und in Ordnung

- **Core-Security & Krypto**: PBKDF2-HMAC-SHA256 (600k Iterationen, Timing-Safe + Dummy-Hash); AES-256-GCM Cookies (`HttpOnly`, `Secure` bei HTTPS, `SameSite=Lax`); Double-Submit-CSRF mit `timingSafeEqual`; konstante Secret-Prüfung bei Mollie- & SPOD-Webhooks; Mollie-Status/Betrag immer serverseitig bei Mollie abgefragt; SFTP mit gepinntem SHA-256-Host-Key; `DOMPurify` für iubenda-HTML; parametrisiertes Kysely-SQL; `lstat`/`realpath`-Pfadprüfung; Admin/Supplier-Routen sauber geschützt (`401`/`403`); `spod-mcp/` ist rein lokal und per `.dockerignore` ausgeschlossen.
- **Bereits behobene Audit-Punkte**: Route-Pattern-Logging (`TODO #1`), `Secure`-Cookies (`TODO #2`), feste Bildgrößen-Allowlist (`TODO #6`), lokale Fonts ohne Google-Requests (`A6`), einheitliche Versandkosten (`A9`), `sourcemap: false`, `lang="de"`, lokalisierte Titel/404 & `robots.txt` (teilweise `C11`), entfernte falsche „ab“-Preise & pauschale Bio-Claims (teilweise `A11`).
- **Struktur**: 1:1-Key-Parität zwischen `de.json` und `en.json` (1180 Zeilen); 38 konsistente DB-Migrationen bis `V39` mit Advisory Lock; Kernablauf (Upload → Zuschnitt → Stil → KI-Generierung → Editor → Warenkorb → Kasse) auf Testinstanz bereits verifiziert.

## Noch ausstehende Prüfungen vor Release

- **Automatisierte Checks** (im Audit-Worktree waren keine `node_modules` installiert):
  - `backend/`: `bun run check` (`typecheck`, `lint`, `format:check`, `bun test` inkl. Testcontainers) + `bun audit`
  - `frontend/`: `bun run build`, `bun run test:unit -- --run`, `bunx eslint .` (ohne `--fix`) + `bun audit`
- **Externe Live-Prüfungen**: Live-Inhalt der iubenda-Policy `48736531`, Mollie-Live-Zahlarten, Mail-Zustellung (SPF/DKIM/DMARC), SPOD-Live-Übergabe & Tassen-SFTP, echte Mobilgeräte (iOS/Android), englische Sprachversion end-to-end.
