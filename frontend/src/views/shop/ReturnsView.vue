<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<!-- The withdrawal notice exists in German only; the English locale shows the binding German text (same pattern as TermsView.vue). -->
<template>
  <section class="bg-background text-foreground">
    <div class="mx-auto max-w-3xl px-6 py-14 sm:px-8 lg:py-20">
      <header class="mb-10 space-y-4">
        <h1 class="text-4xl font-bold leading-tight text-foreground sm:text-5xl">
          {{ t('footerPages.returns.title') }}
        </h1>
        <p class="text-base leading-7 text-foreground-muted">
          {{ t('footerPages.returns.description') }}
        </p>
      </header>

      <article
        lang="de"
        class="space-y-10 text-base leading-7 text-foreground-soft [&_h2]:mb-3 [&_h2]:text-xl [&_h2]:font-semibold [&_h2]:text-foreground [&_p+p]:mt-3"
      >
        <section
          class="rounded-lg border border-primary/30 bg-primary/5 p-6"
          data-testid="returns-personalized-exclusion"
        >
          <h2>Ausschluss des Widerrufsrechts bei personalisierten Produkten</h2>
          <p class="font-medium text-foreground">
            Bei den von uns angebotenen Produkten handelt es sich um Waren, die nach
            Kundenspezifikation angefertigt werden oder eindeutig auf die persönlichen Bedürfnisse
            zugeschnitten sind (§ 312g Abs. 2 Nr. 1 BGB). Hier besteht kein Widerrufsrecht.
          </p>
        </section>

        <section>
          <h2>Widerrufsrecht für Verbraucher</h2>
          <p class="text-sm text-foreground-muted">
            (Verbraucher ist jede natürliche Person, die ein Rechtsgeschäft zu Zwecken abschließt,
            die überwiegend weder ihrer gewerblichen noch ihrer selbstständigen beruflichen
            Tätigkeit zugerechnet werden kann.)
          </p>
        </section>

        <section>
          <h2>Widerrufsbelehrung — Widerrufsrecht bei Standardartikeln</h2>
          <p>
            Sie haben das Recht, bei Standardartikeln binnen vierzehn Tagen ohne Angabe von Gründen
            diesen Vertrag zu widerrufen.
          </p>
          <p>Die Widerrufsfrist bei Standardartikeln beträgt vierzehn Tage ab dem Tag,</p>
          <ul class="mt-3 list-disc space-y-2 pl-6">
            <li>
              an dem Sie oder ein von Ihnen benannter Dritter, der nicht der Beförderer ist, die
              Waren in Besitz genommen haben bzw. hat, sofern Sie eine oder mehrere Waren im Rahmen
              einer einheitlichen Bestellung bestellt haben und diese einheitlich geliefert wird
              bzw. werden, oder
            </li>
            <li>
              an dem Sie oder ein von Ihnen benannter Dritter, der nicht der Beförderer ist, die
              letzte Ware in Besitz genommen haben bzw. hat, sofern Sie mehrere Waren im Rahmen
              einer einheitlichen Bestellung bestellt haben und diese getrennt geliefert werden,
              oder
            </li>
            <li>
              an dem Sie oder ein von Ihnen benannter Dritter, der nicht der Beförderer ist, die
              letzte Teilsendung oder das letzte Stück in Besitz genommen haben bzw. hat, sofern Sie
              eine Ware bestellt haben, die in mehreren Teilsendungen oder Stücken geliefert wird,
              oder
            </li>
            <li>
              an dem Sie oder ein von Ihnen benannter Dritter, der nicht der Beförderer ist, die
              erste Ware in Besitz genommen haben bzw. hat, sofern Sie einen Vertrag zur
              regelmäßigen Lieferung von Waren über einen festgelegten Zeitraum hinweg geschlossen
              haben.
            </li>
          </ul>
          <p class="mt-4">
            Um Ihr Widerrufsrecht auszuüben, müssen Sie uns (JoTo AI GbR, Josef Bauer und Thomas
            Pränger, Wallbergstraße 18, 83607 Holzkirchen, Deutschland, E-Mail-Adresse:
            <a href="mailto:write-us@voenix.shop" class="underline hover:text-primary"
              >write-us@voenix.shop</a
            >) mittels einer eindeutigen Erklärung (z. B. ein mit der Post versandter Brief oder
            E-Mail) über Ihren Entschluss, diesen Vertrag zu widerrufen, informieren. Sie können
            dafür das beigefügte Muster-Widerrufsformular verwenden, das jedoch nicht vorgeschrieben
            ist.
          </p>
          <p>
            Zur Wahrung der Widerrufsfrist reicht es aus, dass Sie die Mitteilung über die Ausübung
            des Widerrufsrechts vor Ablauf der Widerrufsfrist absenden.
          </p>
        </section>

        <section>
          <h2>Folgen des Widerrufs</h2>
          <p>
            Wenn Sie diesen Vertrag widerrufen, haben wir Ihnen alle Zahlungen, die wir von Ihnen
            erhalten haben, einschließlich der Lieferkosten (mit Ausnahme der zusätzlichen Kosten,
            die sich daraus ergeben, dass Sie eine andere Art der Lieferung als die von uns
            angebotene, günstigste Standardlieferung gewählt haben), unverzüglich und spätestens
            binnen vierzehn Tagen ab dem Tag zurückzuzahlen, an dem die Mitteilung über Ihren
            Widerruf dieses Vertrags bei uns eingegangen ist. Für diese Rückzahlung verwenden wir
            dasselbe Zahlungsmittel, das Sie bei der ursprünglichen Transaktion eingesetzt haben, es
            sei denn, mit Ihnen wurde ausdrücklich etwas anderes vereinbart; in keinem Fall werden
            Ihnen wegen dieser Rückzahlung Entgelte berechnet.
          </p>
          <p>
            Wir können die Rückzahlung verweigern, bis wir die Waren wieder zurückerhalten haben
            oder bis Sie den Nachweis erbracht haben, dass Sie die Waren zurückgesandt haben, je
            nachdem, welches der frühere Zeitpunkt ist.
          </p>
          <p>
            Sie haben die Waren unverzüglich und in jedem Fall spätestens binnen vierzehn Tagen ab
            dem Tag, an dem Sie uns über den Widerruf dieses Vertrags unterrichten, an uns
            zurückzusenden oder zu übergeben. Die Frist ist gewahrt, wenn Sie die Waren vor Ablauf
            der Frist von vierzehn Tagen absenden.
          </p>
          <p>Wir tragen die Kosten der Rücksendung der Waren.</p>
          <p>
            Sie müssen für einen etwaigen Wertverlust der Waren nur aufkommen, wenn dieser
            Wertverlust auf einen zur Prüfung der Beschaffenheit, Eigenschaften und Funktionsweise
            der Waren nicht notwendigen Umgang mit ihnen zurückzuführen ist.
          </p>
        </section>

        <section
          class="rounded-lg border border-border bg-background-soft p-6"
          data-testid="returns-withdrawal-form"
        >
          <h2>Muster-Widerrufsformular</h2>
          <p class="text-sm text-foreground-muted">
            (Wenn Sie den Vertrag widerrufen wollen, dann füllen Sie bitte dieses Formular aus und
            senden Sie es zurück.)
          </p>

          <div class="mt-4 space-y-3 border-t border-border pt-4">
            <p>
              – An: JoTo AI GbR, Josef Bauer und Thomas Pränger, Wallbergstraße 18, 83607
              Holzkirchen, Deutschland, E-Mail:
              <a href="mailto:write-us@voenix.shop" class="underline hover:text-primary"
                >write-us@voenix.shop</a
              >
            </p>
            <p>
              – Hiermit widerrufe(n) ich/wir (*) den von mir/uns (*) abgeschlossenen Vertrag über
              den Kauf der folgenden Waren (*) / die Erbringung der folgenden Dienstleistung (*)
            </p>
            <p>– Bestellt am (*) / erhalten am (*)</p>
            <p>– Name des/der Verbraucher(s)</p>
            <p>– Anschrift des/der Verbraucher(s)</p>
            <p>– Unterschrift des/der Verbraucher(s) (nur bei Mitteilung auf Papier)</p>
            <p>– Datum</p>
            <p class="pt-2 text-sm text-foreground-muted">(*) Unzutreffendes streichen.</p>
          </div>
        </section>
      </article>
    </div>
  </section>
</template>
