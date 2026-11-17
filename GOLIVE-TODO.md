# GOLIVE-TODO – Launch-Audit 2026-11-17

Ergebnis der Prüfung der Testinstanz `https://voenix-shop-test-ts.sliplane.app` (alle öffentlichen Seiten, Kernablauf bis zur Kasse ohne Bestellung) und des Codes.
Die rechtlichen Punkte sind eine technische Einschätzung, keine Rechtsberatung.
Nummern = Priorität. A und B vor dem Go-live, C vor Werbung und echtem Traffic, D am Go-live-Tag.

## A. Rechtliche Blocker

- [ ] **1. Datenschutzerklärung fehlt** – `frontend/src/views/shop/PrivacyView.vue`
  `/privacy` zeigt nur „Hier ergänzen wir bald …“. Die iubenda-Erklärung der alten Seite passt nicht (Stripe, Tag Manager, Facebook-Pixel).
  Fix: Text erstellen lassen und einbauen. Muss abdecken: Sliplane (Hosting), Mollie, Sweego (Mail), KI-Dienst (Foto-Upload, Drittland), SPOD und Tassen-Lieferant (Adressdaten), Cookies (Session, XSRF, Gast-Warenkorb), Kundenkonto.
- [ ] **2. AGB fehlen auf der Seite** – `frontend/src/views/shop/TermsView.vue`, `frontend/src/views/shop/CheckoutView.vue:464`
  `/terms` ist ein Platzhalter. Die Kasse verlinkt auf `https://voenix.shop/pdf/AGBs.pdf` beim alten WordPress-Host; nach dem Domain-Umzug ist der Link tot.
  Die PDF (Stand 18.09.2025) ist veraltet: Münchner Straße 17 und infos@ statt Wallbergstr. 18 und write-us@; Lieferzeit „in der Regel bis zu 7 Werktage“ gegen „3–5 Tage“ auf der Startseite; Ziffer 4.4 leer; Ziffer 9.2 Rügepflicht für Verbraucher; Rechtswahl ohne Verbrauchervorbehalt trotz EU-Versand; keine Angaben zu Vertragstext-Speicherung und Vertragssprache.
  Fix: AGB neu erstellen lassen, auf `/terms` anzeigen, Kasse auf `/terms` verlinken.
- [ ] **3. Widerrufsinfo fehlt** – `frontend/src/views/shop/ReturnsView.vue`, `CheckoutView.vue`
  `/returns` ist ein Platzhalter; die Versandseite verlinkt darauf als „Widerruf & Retouren“. Auch bei personalisierter Ware ist vor der Bestellung auf den Ausschluss des Widerrufsrechts hinzuweisen (§ 312g Abs. 2 Nr. 1 BGB).
  Fix: Text auf `/returns`, Hinweis mit Link in der Kasse. Der Widerrufsbutton (Pflicht seit 19.06.2026) ist nur nötig, sobald ein nicht personalisiertes Produkt verkauft wird.
- [ ] **4. Bestellbestätigung ohne Vertragsinfos** – `backend/src/modules/email/templates/order-confirmation.ts`
  Die Mail enthält weder AGB noch Widerrufshinweis noch Anbieterangaben; die müssen dem Kunden auf einem dauerhaften Datenträger zugehen.
  Fix: AGB und Widerrufshinweis als Text oder Anhang, Anbieterangaben in den Mail-Footer (`templates/layout.ts`).
- [ ] **5. Impressum unvollständig** – `frontend/src/views/shop/ImprintView.vue`
  Telefonnummer fehlt; E-Mail allein reicht im Fernabsatz nicht. `/contact` ist ein Platzhalter.
  Fix: Telefonnummer ergänzen.
- [ ] **6. Google Fonts von Google-Servern** – `frontend/index.html:14-16`
  Lädt `fonts.googleapis.com` und `fonts.gstatic.com` ohne Einwilligung (bekannter Abmahngrund).
  Fix: Schriften lokal ausliefern (woff2 + `@font-face`), die drei `<link>`-Zeilen löschen. Danach ist kein Cookie-Banner nötig: keine Tracker gefunden.
- [ ] **7. Preisangaben ohne MwSt.- und Versandhinweis** – Produktkarten, `CartView.vue`, `CheckoutView.vue`
  Produktkarten zeigen „17,95 €“ ohne „inkl. MwSt., zzgl. Versand“; Warenkorb und Kasse zeigen „Gesamt“ ohne MwSt.-Hinweis.
  Fix: Hinweis an Preis und Gesamtsumme, „zzgl. Versand“ mit Link auf `/shipping`.
- [ ] **8. Kasse: wesentliche Merkmale und Lieferzeit fehlen** – `CheckoutView.vue:409`
  Die Bestellübersicht zeigt nur „Paris, altrosa × 1“.
  Fix: Produktart mit Kernangaben (z. B. „Keramiktasse 300 ml, personalisiert“), Motiv-Vorschau, Lieferzeit.
- [ ] **9. Versandkosten widersprechen sich** – `backend/src/modules/cart/totals.ts:5-8`, `frontend/src/i18n/locales/de.json` (`footerPages.shipping.content.cards`), `en.json`
  Versandseite: DE 4,95 €, EU 9,95 € (frei ab 75 €). Berechnet wird überall 4,90 €, frei ab 50 €.
  Fix: eine Regel festlegen und Seite oder Berechnung angleichen.
- [ ] **10. Fest einprogrammierte Kundenstimmen** – `de.json:507`, `de.json:651`, `en.json`, `HomeView.vue`, `RoyalDogView.vue`
  Markus T., Sarah K., Julia M. (Startseite) und Lena M., Tom B., Anna K. (`/royal-dog`). Erfundene Bewertungen sind immer unzulässig; bei echten fehlt der Hinweis zur Prüfung.
  Fix: beide Abschnitte entfernen, bis es echte Bewertungen gibt.
- [ ] **11. Werbeaussagen ohne Deckung** – `de.json:568-634`, `de.json:782`, `en.json`, `components/shop/Footer.vue`
  - „Made in Germany“ und „Wir drucken und versenden per DHL aus Deutschland“: für SPOD-Artikel prüfen, sonst streichen.
  - Poster („ab 14,99 €“) und Karten („Als Karte“) sind nicht im Sortiment.
  - „ab“-Preise falsch: T-Shirts „ab 29,99 €“ (real 19,99 €), Keramiktasse „ab 19,99 €“ (real 15,95 €), Thermobecher „ab 24,99 €“ (real 23,95 €).
  - „Bio-Baumwolle, Fair Trade“ pauschal für alle T-Shirts; nur eines ist Bio. Umweltaussagen ohne Beleg sind seit 27.09.2026 verboten.
  - Zahlungslogos im Footer (Visa, Mastercard, Apple Pay, PayPal, Amex, Klarna) und „Sichere Bezahlung mit Klarna, PayPal & Kreditkarte“ müssen zu den in Mollie aktivierten Zahlarten passen.
  - „Versand in 3–5 Tagen“ muss zur Lieferzeit in den AGB passen.
- [ ] **12. Stilnamen mit fremden Rechten** – Admin → Prompts
  „Hundertwasser“ und „Picasso“ (beide noch geschützt, Rechteinhaber verfolgen die Namensnutzung), „Funky Pop Toy Style“, „Instagramm Beauty“.
  Fix: umbenennen. „Van Gogh“ ist unkritisch.
- [ ] **13. Herstellerangaben fehlen** – Artikelbeschreibungen (Admin)
  Die Produktsicherheitsverordnung verlangt im Angebot Name, Anschrift und E-Mail des Herstellers. Tassen: nichts; SPOD: nur „Marke: Stanley/Stella“.
  Fix: Herstellerangaben je Artikel ergänzen und im Shop anzeigen.
- [ ] **14. Foto-Upload ohne Hinweis** – `frontend/src/components/shop/wizard/steps/UploadImageStep.vue`
  Kein Hinweis zu Bildrechten, abgebildeten Personen und KI-Verarbeitung.
  Fix: ein Satz unter dem Upload-Feld mit Link auf Datenschutzerklärung und AGB.

## B. Funktionale Blocker

- [ ] **15. Platzhalterseiten im Footer** – `AboutView.vue`, `FaqView.vue`, `ContactView.vue`, `SupportView.vue`, `PaymentView.vue`
  Alle zeigen „Hier findest du bald …“ und sind auf jeder Seite verlinkt.
  Fix: Inhalt liefern oder Link aus `Footer.vue` entfernen. `/payment` braucht die Zahlarten.
- [ ] **16. Stil-Aufpreis wird angezeigt, aber nie berechnet** – `frontend/src/components/shop/editor/ProductEditor.vue:480`
  Der Assistent zeigt „3,95 €“ pro Stil; `addToCart` übergibt keine `promptId`, im Warenkorb steht `promptPrice: 0`.
  Fix: `promptId` aus dem Entwurf übergeben oder die Preisanzeige im Stil-Schritt entfernen.
- [ ] **17. Magic Coins halb fertig** – `components/shop/Header.vue:111`, `components/shop/MobileMenu.vue:381`, `views/shop/MagicCoinsView.vue`
  Header verlinkt für alle auf `/magic-coins` mit Paketpreisen (7,90 / 19,90 / 34,90 €); der Kauf zeigt nur einen Toast.
  Fix: Kaufpakete ausblenden, bis der Kauf existiert.
- [ ] **18. `/royal-dog` verspricht einen Stil, den es nicht gibt** – `frontend/src/views/shop/RoyalDogView.vue:13`
  `ROYAL_DOG_PROMPT_ID` ist `null`; der Button öffnet den normalen Assistenten.
  Fix: Prompt anlegen und ID setzen, oder keine Werbung auf die Seite schalten.
- [ ] **19. Offene Sicherheitspunkte aus `TODO.md`**
  Im Code als weiterhin offen bestätigt: #3 (deaktivierte Artikel bestellbar), #4 (Mengenerhöhung zum alten Aktionspreis), #7 (Service Worker cacht private API-Antworten), #8 (kein Rate-Limit auf Login/Registrierung/Passwort-Reset), #9 (Kontoübernahme vor Registrierung). #5 (Einmal-Gutschein mehrfach nutzbar) steht dort offen, nicht nachgeprüft.
  Fix: mindestens #4, #7, #8 vor dem Go-live.
- [ ] **20. Testdaten im Katalog** – Admin
  - Zehn Tassen mit Langbeschreibung wie „.“, „asf“, „qwef“, „sdg“.
  - Tippfehler in Stilnamen: „Stylizet Anime“, „Expressive Oil Paining“, „Instagramm Beauty“.
  - Kategorie „Valentinstag“ (7,08 €) im November, „Western Comic“ mit 0,00 €.
  - Stil- und Kategorienamen englisch („realistic Painting“) in einem deutschen Shop.

## C. Technik

- [ ] **21. Keine Sicherheits-Header** – `backend/src/platform/http/app.ts`
  HSTS, `X-Content-Type-Options`, Clickjacking-Schutz und `Referrer-Policy` fehlen.
  Fix: Hono `secureHeaders()`.
- [ ] **22. Source Maps öffentlich** – `frontend/vite.config.ts:139`
  `/assets/*.js.map` ist abrufbar.
  Fix: `sourcemap: false` oder `'hidden'`.
- [ ] **23. Keine Kompression, Admin-Bundle für alle** – `backend/src/platform/http/app.ts`, `frontend/vite.config.ts:83`
  Rund 1,3 MB JavaScript unkomprimiert beim ersten Besuch, davon 446 KB Admin.
  Fix: Kompression (Hono `compress()` oder am Proxy) und `TODO.md` #17.
- [ ] **24. Sprache und SEO**
  - `frontend/index.html:2`: `<html lang="">` leer; Meta-Description und OG-Tags fehlen.
  - Seitentitel englisch („Privacy Policy | Voenix“, „Cart“) – `frontend/src/router/shop.ts`.
  - 404-Seite englisch – `frontend/src/views/NotFoundView.vue`.
  - Ländernamen in der Kasse englisch („Germany“).
  - `manifest.webmanifest` mit `lang: en` – `frontend/vite.config.ts`.
- [ ] **25. `robots.txt` und `sitemap.xml` fehlen** – `frontend/public/`
  Beide liefern die App-Seite; jede unbekannte URL antwortet mit Status 200.
  Fix: `robots.txt` anlegen. Testinstanz nach dem Go-live abschalten oder auf `noindex` setzen.

## D. Domain-Umzug und Betrieb

- [ ] **26. Weiterleitungen für alte WordPress-URLs**
  `/impressum/`, `/widerruf/`, `/versand/`, `/zahlung/`, `/faq/`, `/ueber-uns/`, `/tassenuebersicht/`, `/firmenkunden/`, `/pdf/AGBs.pdf`.
- [ ] **27. Produktiv-Konfiguration**
  `FRONTEND_BASE_URL` auf die Live-Domain, `MOLLIE_API_KEY` (Live-Schlüssel), `MOLLIE_WEBHOOK_URL` auf die Live-Domain, `EMAIL_ENABLED=true`, `GENERATOR_DUMMY_MODE=false`.
- [ ] **28. Mail-Zustellung prüfen**
  SPF von voenix.shop nennt nur IONOS und Outlook, nicht Sweego; DMARC steht auf `p=none`. DKIM und Return-Path für Sweego prüfen, sonst landen Bestellmails im Spam.
- [ ] **29. Echte Testbestellung**
  Einmal komplett mit Live-Schlüssel: Zahlung, Bestätigungsmail, Bestell-Link, Übergabe an SPOD oder Lieferant, Versandmail.

## E. Außerhalb der Seite klären

- [ ] **30. Auftragsverarbeitungsverträge** mit Sliplane, Sweego, dem KI-Dienst und SPOD.
- [ ] **31. Verpackungsregistrierung** (LUCID) klären.
- [ ] **32. EU-Versand**: Umsatzsteuer (OSS) und Verpackungsregistrierungen im Ausland. Alternative: zunächst nur nach Deutschland liefern (Länder im Admin).

## Geprüft und in Ordnung

- Kernablauf live: Foto hochladen, zuschneiden, Stil wählen, KI-Generierung (ca. 15 s), Editor, Warenkorb, Kassenformular.
- Alle 20 Produkte und Bilder laden; bei 390 px Breite kein horizontales Überlaufen.
- `/orders`, `/profile`, `/admin`, `/supplier` leiten auf den Login um; Admin-API antwortet mit 401.
- HTTPS-Umleitung; Cookies mit `HttpOnly`, `Secure`, `SameSite=Lax`.
- Bestellbutton „Kostenpflichtig bestellen“.
- Bildgrößen-Beschränkung aktiv (`TODO.md` #6).
- Materialangaben bei Textilien vorhanden.

## Nicht geprüft

Zahlung und Mollie-Modus, Mailversand, SPOD-Übergabe, Registrierung und Login, Admin-Bereich, englische Sprachversion, echte Geräte.
