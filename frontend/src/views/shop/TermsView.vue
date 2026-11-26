<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

const { t } = useI18n()
</script>

<!-- The AGB exist only in German (VOENIX_shop_AGB_11-2026.docx); the English locale shows the binding German text. -->
<template>
  <section class="bg-background text-foreground">
    <div class="mx-auto max-w-3xl px-6 py-14 sm:px-8 lg:py-20">
      <header class="mb-10 space-y-4">
        <h1 class="text-4xl font-bold leading-tight text-foreground sm:text-5xl">
          {{ t('footerPages.terms.title') }}
        </h1>
        <p class="text-base leading-7 text-foreground-muted">
          {{ t('footerPages.terms.description') }}
        </p>
      </header>

      <article
        lang="de"
        class="space-y-10 text-base leading-7 text-foreground-soft [&_h2]:mb-3 [&_h2]:text-xl [&_h2]:font-semibold [&_h2]:text-foreground [&_p+p]:mt-3"
      >
        <section>
          <h2>1. Geltungsbereich</h2>
          <p>
            (1) Diese Allgemeinen Geschäftsbedingungen (AGB) gelten für alle Verträge, die Sie mit
            uns über den Onlineshop VOENIX.shop schließen. Vertragspartnerin ist die JoTo AI GbR,
            Wallbergstraße 18, 83607 Holzkirchen, Deutschland. Die Gesellschafter sind Josef Bauer
            und Thomas Pränger. Sie erreichen uns per E-Mail unter
            <a href="mailto:write-us@voenix.shop" class="underline hover:text-primary"
              >write-us@voenix.shop</a
            >.
          </p>
          <p>
            (2) Gegenüber Unternehmern gelten deren abweichende oder ergänzende Geschäftsbedingungen
            nur, wenn wir ihrer Geltung ausdrücklich zustimmen. Individuelle Vereinbarungen haben
            Vorrang.
          </p>
        </section>

        <section>
          <h2>2. Leistungen</h2>
          <p>
            (1) Über VOENIX.shop können Sie persönliche Bilder hochladen und auf unseren
            Shop-Produkten wie Tassen, T-Shirts etc drucken lassen. Je nach gewähltem Angebot
            bearbeiten wir das Bild mithilfe künstlicher Intelligenz (KI) im ausgewählten Stil.
          </p>
          <p>
            (2) Bei der KI-Bearbeitung können sich Farben, Formen und Bilddetails gegenüber dem
            Originalfoto verändern. Wenn Sie im Bestellvorgang ein konkretes Motiv für den Druck
            auswählen oder freigeben, verwenden wir dieses Motiv als Druckvorlage. Die gesetzlichen
            Mängelrechte bleiben unberührt.
          </p>
        </section>

        <section>
          <h2>3. Vertragsschluss</h2>
          <p>
            (1) Die Produktdarstellungen im Onlineshop laden zur Abgabe einer Bestellung ein. Mit
            Anklicken der Schaltfläche, mit der Sie die zahlungspflichtige Bestellung abschließen,
            geben Sie ein verbindliches Kaufangebot für die Waren in Ihrem Warenkorb ab.
          </p>
          <p>
            (2) Wir bestätigen Ihnen den Eingang der Bestellung unverzüglich per E-Mail. Eine reine
            Eingangsbestätigung stellt für sich genommen keine Annahme Ihres Kaufangebots dar.
          </p>
          <p>
            (3) Wenn wir Sie im Bestellvorgang oder per E-Mail zur Zahlung auffordern, nehmen wir
            Ihr Angebot mit der Zahlungsaufforderung an. Bei einer Zahlungsart, bei der die Zahlung
            direkt mit dem Abschluss der Bestellung ausgelöst wird, kommt der Vertrag zu diesem
            Zeitpunkt zustande.
          </p>
          <p>
            (4) In den übrigen Fällen nehmen wir Ihr Angebot innerhalb von fünf Kalendertagen nach
            Eingang der Bestellung an, indem wir Ihnen die Annahme oder den Versand per E-Mail
            bestätigen oder Ihnen die Ware zustellen. Maßgeblich ist, welches dieser Ereignisse
            zuerst eintritt. Erfolgt innerhalb dieser Frist keine Annahme, sind Sie an Ihr Angebot
            nicht mehr gebunden.
          </p>
        </section>

        <section>
          <h2>4. Individualisierte Produkte und Bilddateien</h2>
          <p>
            (1) Sie dürfen nur Bilder und Inhalte hochladen, für deren Nutzung im Rahmen der
            Bestellung Sie die erforderlichen Rechte und gegebenenfalls die erforderlichen
            Einwilligungen haben. Dazu gehören insbesondere Urheberrechte, Markenrechte und Rechte
            abgebildeter Personen. Die hochgeladenen Inhalte dürfen keine gesetzlichen Vorschriften
            oder Rechte Dritter verletzen.
          </p>
          <p>
            (2) Soweit es für Ihre Bestellung erforderlich ist, gestatten Sie uns, die
            bereitgestellten Inhalte zu speichern, zu vervielfältigen, zu bearbeiten und für den
            Druck zu verwenden. Diese Gestattung umfasst auch die Weitergabe an die zur Bearbeitung
            oder Herstellung eingesetzten Dienstleister, soweit dies zur Ausführung Ihrer Bestellung
            erforderlich ist.
          </p>
          <p>
            (3) Vor Annahme der Bestellung können wir das Angebot ablehnen, wenn konkrete
            Anhaltspunkte dafür bestehen, dass die hochgeladenen Inhalte rechtswidrig sind oder
            Rechte Dritter verletzen.
          </p>
        </section>

        <section>
          <h2>5. Preise und Zahlungsbedingungen</h2>
          <p>
            (1) Alle Preise enthalten die gesetzliche Mehrwertsteuer. Gegebenenfalls anfallende
            Versandkosten weisen wir gesondert aus. Der Gesamtpreis einschließlich der Versandkosten
            wird Ihnen vor Abgabe der Bestellung angezeigt.
          </p>
          <p>
            (2) Die für Ihre Bestellung verfügbaren Zahlungsmittel zeigen wir Ihnen zu Beginn des
            Bestellvorgangs an.
          </p>
        </section>

        <section>
          <h2>6. Lieferung und Versand</h2>
          <p>(1) Die Lieferung erfolgt an die vom Kunden angegebene Lieferadresse.</p>
          <p>
            (2) Die Lieferzeit beträgt in der Regel bis zu 7 Werktage ab Zahlungseingang, kann sich
            aber bei individualisierten Produkten verlängern.
          </p>
          <p>
            (3) Wir sind zu Teillieferungen berechtigt, soweit dies für den Kunden zumutbar ist.
          </p>
        </section>

        <section>
          <h2>7. Widerrufsrecht bei individualisierten Produkten</h2>
          <p>
            (1) Bei Verträgen über Waren, die nach Ihrer individuellen Auswahl angefertigt werden
            oder eindeutig auf Ihre persönlichen Bedürfnisse zugeschnitten sind, besteht für
            Verbraucher unter den Voraussetzungen des § 312g Abs. 2 Nr. 1 BGB kein Widerrufsrecht.
            Dazu gehören insbesondere alle Shop-Produkte, die wir mit Ihrem Foto oder einem daraus
            erstellten Motiv bedrucken.
          </p>
          <p>
            (2) Soweit Ihnen für andere Produkte ein gesetzliches Widerrufsrecht zusteht,
            informieren wir Sie darüber gesondert. Die gesetzlichen Mängelrechte gelten auch für
            individualisierte Produkte.
          </p>
        </section>

        <section>
          <h2>8. Eigentumsvorbehalt</h2>
          <p>(1) Die Ware bleibt bis zur vollständigen Bezahlung unser Eigentum.</p>
        </section>

        <section>
          <h2>9. Gewährleistung</h2>
          <p>(1) Es gelten die gesetzlichen Gewährleistungsbestimmungen.</p>
        </section>

        <section>
          <h2>10. Haftung</h2>
          <p>
            (1) Bei Vorsatz, grober Fahrlässigkeit und bei Schäden aus der schuldhaften Verletzung
            des Lebens, des Körpers oder der Gesundheit haften wir nach den gesetzlichen
            Vorschriften.
          </p>
          <p>
            (2) Bei leichter Fahrlässigkeit haften wir im Übrigen nur bei Verletzung wesentlicher
            Vertragspflichten. Das sind Pflichten, deren Erfüllung die ordnungsgemäße Durchführung
            des Vertrags überhaupt erst ermöglicht und auf deren Einhaltung Sie regelmäßig vertrauen
            dürfen. In diesem Fall begrenzen wir die Haftung auf den bei Vertragsschluss
            vorhersehbaren, vertragstypischen Schaden.
          </p>
          <p>
            (3) Ansprüche nach dem Produkthaftungsgesetz, aus einer ausdrücklich übernommenen
            Garantie und wegen arglistig verschwiegener Mängel bleiben unberührt.
          </p>
        </section>

        <section>
          <h2>11. Rechte an der Software und am Druckmotiv</h2>
          <p>
            (1) Der Kauf eines individualisierten Produkts räumt Ihnen keine Nutzungsrechte an der
            von uns eingesetzten Software ein. Ihre Rechte an den von Ihnen bereitgestellten Bildern
            bleiben unberührt.
          </p>
          <p>
            (2) Sie erhalten das individualisierte Produkt mit dem vereinbarten Druckmotiv. Eine
            separate Bilddatei oder weitergehende Nutzungsrechte am bearbeiteten Motiv gehören nur
            dann zum Leistungsumfang, wenn dies ausdrücklich vereinbart ist.
          </p>
        </section>

        <section>
          <h2>12. Datenschutz</h2>
          <p>
            (1) Informationen zur Verarbeitung personenbezogener Daten, insbesondere hochgeladener
            Fotos, finden Sie in der
            <RouterLink :to="{ name: 'privacy' }" class="underline hover:text-primary"
              >Datenschutzerklärung</RouterLink
            >
            auf VOENIX.shop.
          </p>
        </section>

        <section>
          <h2>13. Anwendbares Recht</h2>
          <p>
            (1) Es gilt deutsches Recht unter Ausschluss des UN-Kaufrechts. Sind Sie Verbraucher,
            bleiben die zwingenden Verbraucherschutzvorschriften des Staates, in dem Sie Ihren
            gewöhnlichen Aufenthalt haben, unberührt, soweit sie ohne diese Rechtswahl anwendbar
            wären.
          </p>
        </section>
      </article>
    </div>
  </section>
</template>
