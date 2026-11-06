# TODO – Audit 2026-11-02

Ergebnis eines Repo-Audits (Annahme: kleiner Shop, eine Render-Instanz, wenige Dutzend gleichzeitige Nutzer).
Nummern = Priorität. Zuerst 1 + 2, dann 3–7 vor echtem Traffic.

## Must fix

- [ ] **1. Webhook-Secrets und Bestell-Links im Log** – `backend/src/platform/http/app.ts:33-39`
  Logger schreibt `c.req.path`; Secrets stehen im Pfad (`/api/payments/webhook/:secret`, `/api/production/webhooks/spod/:secret`, `/api/order-lookup/:token`).
  Fix: `c.req.routePath` loggen. Danach Mollie- und SPOD-Webhook-Secret rotieren.
- [ ] **2. Auth-Cookies ohne `Secure` in Produktion** – `backend/src/platform/auth/auth.ts:119`
  `isHttps` liest `c.req.url`; Render terminiert TLS am Proxy, Container sieht nur HTTP.
  Fix: `secure: true` außer bei Host `localhost`.
- [ ] **3. Deaktivierte Artikel bleiben bestellbar** – `backend/src/modules/order/service.ts:141-144`
  Placement prüft nur `catalog.has`, nicht `purchasable`.
  Fix: Zeile mit `purchasable !== true` ablehnen (`unknownArticle`), plus Test.
- [ ] **4. Mengenerhöhung zum alten Aktionspreis** – `backend/src/modules/cart/repository.ts:149-158`
  `updateQuantity` ändert nur die Menge; 0-€-Zeile aus einer 100-%-Aktion → 99 Stück gratis, PAID ohne Zahlung.
  Fix: beim Mengenwechsel heutigen Preis neu schreiben, nicht kaufbar → ablehnen. Plus Test.
  Achtung: widerspricht `cart-package.md` („Snapshots ändern sich nie“) – Entscheidung anpassen.
- [ ] **5. Einmal-Gutschein mehrfach nutzbar über „erneut bezahlen“** – `backend/src/modules/order/payment/service.ts` (startPayment), `order/transitions.ts:49-59`
  Mehrere Bestellungen mit Code anlegen, jeweils abbrechen (Reservierung frei), dann alle bezahlen → nur erste Einlösung klappt, Rest wird trotzdem PAID.
  Fix: in `startPayment` unter Order-Lock neu reservieren; bei Ablehnung keine Zahlung (`promotionRejected`).
  Achtung: `checkout-package.md:274-277` akzeptiert „PAID ohne Einlösung“ nur als seltenes Race.
- [ ] **6. Öffentliche Bild-Route: beliebige Größen, Cache wächst endlos** – `backend/src/modules/image/codec.ts:51-67`
  Jede Größe bis 4096×4096 anonym, kleine Bilder werden hochskaliert, Cache wird nie geleert → Platte/Speicher voll.
  Fix: nur feste Größen erlauben (80, 200, 320, 400, 1000, 1600).
- [ ] **7. Service Worker cacht private API-Antworten über Logout hinaus** – `frontend/vite.config.ts:35-42`
  `NetworkFirst` auf `/api/` speichert `/api/auth/me`, Bestellungen, Bestell-Links; bei Netzfehler sieht der nächste Nutzer die Daten.
  Fix: `/api/`-Eintrag aus `runtimeCaching` löschen.
- [ ] **8. Kein Rate-Limit auf Login/Register/Forgot-Password/Resend-Confirmation** – `backend/src/modules/account/routes.ts:188-234`
  Mail-Bombing über Sweego; 15 Fehlversuche sperren Admin wiederholt aus.
  Fix: vorhandenen `createClientIpRateLimiter` vor diese Routen und vor `POST /api/cart/promotion` (Gutschein-Raten).
- [ ] **9. Kontoübernahme vor Registrierung** – `backend/src/modules/account/service.ts:127-143`, `:198-210`
  Angreifer registriert fremde Adresse unbestätigt; Opfer bestätigt später per Resend → Angreifer-Passwort gilt.
  Fix: Register auf unbestätigtes Konto ersetzt den Passwort-Hash; `resetPassword` setzt zusätzlich `email_confirmed = true, failed_login_count = 0, locked_until = null`.

## Should fix

- [ ] **10. Generator-Limit ohne globale Obergrenze** – `backend/src/platform/ratelimit/client-ip-rate-limiter.ts:35-63`
  20/h pro IP, bis 100k IPs; rotierende IPs = unbegrenzte fal.ai-Kosten, volle Tabelle sperrt alle aus.
  Fix: zusätzlicher prozessweiter Zähler pro Stunde.
- [ ] **11. Admin-Session nicht widerrufbar** – `backend/src/platform/auth/auth.ts:49-64`
  Gestohlenes Admin-Cookie bleibt nach Logout/Passwortwechsel/Rollenentzug gültig.
  Fix: `requireAdmin` liest Rollen aus der DB (wie `requireSupplier`).
  Achtung: Auth-Guide hält „keine Revocation“ bewusst fest – nur zusammen mit #2 riskant.
- [ ] **12. Wizard behält alte Motive nach Artikel-/Foto-/Stilwechsel** – `frontend/src/stores/shop/imageGeneration.ts:61-127`, `components/shop/wizard/steps/GenerateStep.vue:132-145`
  Tassen-Motiv landet im Shirt-Editor; verspätetes Ergebnis landet in neuem Wizard.
  Fix: Generationszähler (`reset()` erhöht, veraltete Ergebnisse verwerfen) + `watch` in `WizardView` ruft `reset()`.
- [ ] **13. Warenkorb-Ladefehler sieht aus wie „leer“** – `frontend/src/stores/shop/cart.ts:170-179`, `views/shop/CartView.vue`, `views/shop/CheckoutView.vue:46-50`
  Fix: bei `error && isEmpty` Hinweis + Neu laden; Checkout nur ohne Fehler umleiten.
- [ ] **14. Lieferanten-PDF nutzt heutigen Lieferanten statt Split-Snapshot** – `backend/src/modules/production/delivery/generation.ts:192-197`, `production/pdf/renderer.ts:83`
  Artikel wird zwischen Split und Generierung umgehängt → Zeile fehlt im PDF oder Job hängt still.
  Fix: `ITEM_SET_CHANGED`-Prüfung wie in `production/spod/submitter.ts:69-80`.
- [ ] **15. SPOD-Webhook-Fallback trifft falschen Job** – `backend/src/modules/production/fulfillment/service.ts:222-235`
  Zwei SPOD-Orders mit gleicher Referenz nach Timeout; Cancel der verwaisten storniert den echten Job.
  Fix: Fallback nur, wenn gespeicherte `externalReference` leer ist oder übereinstimmt.
- [ ] **16. `migrate-legacy-data.sh` leert Ziel ohne Rückfrage** – `scripts/migrate-legacy-data.sh:560-580`
  `TRUNCATE … CASCADE` löscht auch Bestellungen/Zahlungen, ohne Transaktion.
  Fix: abbrechen, wenn Host nicht `localhost`, außer `--yes-wipe-target`.
- [ ] **17. Startseite lädt komplette Admin-App** – `frontend/vite.config.ts:97-116`
  Chunk-Gruppen `admin`/`shop`/`shared` schalten Route-Lazy-Loading aus (~1,3 MB vorab).
  Fix: die drei Gruppen löschen, nur `vendor`-Gruppen behalten.
- [ ] **18. Toter Code und Kopien**
  - Ungenutzte Deps in `frontend/package.json`: `@iconify/vue`, `@tanstack/vue-table`, `radix-vue` (auch aus Regex in `vite.config.ts:88`), `vite-plugin-vue-devtools`.
  - `frontend/src/composables/useImageContainRect.ts` ungenutzt → löschen.
  - `readCapped` doppelt (`backend/src/modules/spod/client.ts:438`, `generator/fal.ts:129`) → nach `platform/fetch.ts`.
  - `MAX_IMAGE_BYTES` dreifach (`image/codec.ts:14`, `generator/upload.ts:12`, `spod/client.ts:150`) → aus `image/codec.ts` importieren.

## Nice to have

- [ ] **19. Doppelklick +/− im Warenkorb setzt falsche Menge** – `frontend/src/components/shop/CartLineItem.vue:162-177`
  Fix: Zeilen-Buttons sperren, solange Request läuft.
- [ ] **20. Doppelklick „Bestellen“ während Mollie-Redirect** – `frontend/src/views/shop/CheckoutView.vue:268-283`
  Fix: lokales Flag, das im Erfolgsfall nicht zurückgesetzt wird.

## Kleinkram (nicht priorisiert)

- [ ] Checkout liest Warenkorb vor dem Lock – `backend/src/modules/checkout/service.ts:157` vs. `:110`.
- [ ] PENDING-Bestellung ohne Zahlung verschluckt spätere Warenkorb-Änderungen (`alreadyPlaced`) – `checkout/service.ts:131-134`.
- [ ] Wiederholte Ops-Mails bei wechselnd permanentem/temporärem Fehler – `backend/src/modules/production/spod/repository.ts:151-162`.
- [ ] E-Mail-Änderung nur in Groß-/Kleinschreibung → 409 – `backend/src/modules/account/service.ts:231-245`.
- [ ] Auth-Store behandelt jeden Fehler als Logout, kein 401-Handling – `frontend/src/stores/shared/auth.ts:186-194`, `frontend/src/lib/api.ts`.
- [ ] Unerreichbare `userId === undefined`-Zweige – `backend/src/modules/account/routes.ts:248-289, 356`.
- [ ] ID-Param-Validator fünfmal kopiert (`cart`, `checkout`, `order`, `operations`, `production/fulfillment` routes) → `integerParam` in `platform/http/respond.ts` mit Status-Option.

## Nicht geprüft

Tests nicht ausgeführt; meiste Admin-Views, Editor-Interna, Migrationen V19–V36, `pricing/repository.ts`, Prompt-Modul, Mug-/Kategorie-Artikel, `production/fulfillment/repository.ts` ungelesen.
