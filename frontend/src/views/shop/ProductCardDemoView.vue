<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue'
import ProductCard from '@/components/shop/ProductCard.vue'
import ProductCardCalm from '@/components/shop/product-card-demo/ProductCardCalm.vue'
import ProductCardEditorial from '@/components/shop/product-card-demo/ProductCardEditorial.vue'
import ProductCardOverlay from '@/components/shop/product-card-demo/ProductCardOverlay.vue'
import ProductCardStrip from '@/components/shop/product-card-demo/ProductCardStrip.vue'
import ProductCardStructured from '@/components/shop/product-card-demo/ProductCardStructured.vue'
import { createDemoShirts } from '@/components/shop/product-card-demo/demoFixtures'
import { Button } from '@/components/ui/button'
import { useCatalogStore, type TshirtDto } from '@/stores/shop/catalog'

/**
 * Development-only comparison page for redesigns of the shirt product card. It shows the current
 * card next to five alternatives, fed by the live catalog when the backend answers and by local
 * fixtures otherwise. None of the alternatives is used anywhere else yet.
 */
const catalogStore = useCatalogStore()
onMounted(() => catalogStore.fetchArticles())

const fixtureShirts = createDemoShirts()
const usesLiveCatalog = computed(() => catalogStore.tshirts.length > 0)
const shirts = computed<TshirtDto[]>(() =>
  usesLiveCatalog.value ? catalogStore.tshirts : fixtureShirts,
)

/** The current card is controlled, so the page keeps its selection per article. */
const originalSelection = reactive<Record<number, number>>({})
function originalVariant(article: TshirtDto) {
  const id = originalSelection[article.id]
  return (
    article.variants.find((variant) => variant.id === id) ??
    article.variants.find((variant) => variant.isDefault) ??
    article.variants[0] ??
    null
  )
}

const alternatives = [
  {
    id: 'calm',
    title: 'A · Ruhig',
    description:
      'Keine Beschreibung, fünf große Farbfelder mit „+n“ zum Aufklappen, aktive Farbe als Text, Größen als Spanne.',
    component: ProductCardCalm,
  },
  {
    id: 'overlay',
    title: 'B · Overlay',
    description:
      'Farbauswahl als horizontal scrollbare Glas-Leiste direkt auf dem Bild, Preis als Tag oben links, darunter nur eine Meta-Zeile (Farbe · Anzahl · Größenspanne).',
    component: ProductCardOverlay,
  },
  {
    id: 'structured',
    title: 'C · Strukturiert',
    description:
      'Abschnitte mit Überschrift und Trennlinie. Farbfelder füllen ein Raster über die volle Kartenbreite, Größen sortiert als Chips.',
    component: ProductCardStructured,
  },
  {
    id: 'editorial',
    title: 'D · Editorial',
    description:
      'Großes Bild im Schaufenster-Stil, Farben nur als Zusammenfassung (Stapel + Anzahl). Die Farbwahl passiert erst im nächsten Schritt.',
    component: ProductCardEditorial,
  },
  {
    id: 'strip',
    title: 'E · Farbleiste',
    description:
      'Alle Farben als Kapsel-Leiste über die volle Breite; die gewählte Farbe wächst. Darunter Farbname und die sortierte Größenliste.',
    component: ProductCardStrip,
  },
]
</script>

<template>
  <div class="grid gap-12 pb-16">
    <header class="grid gap-3">
      <p class="text-xs font-semibold uppercase tracking-[0.16em] text-primary">Entwicklung</p>
      <h1 class="text-3xl font-bold text-foreground">Produktkarten-Alternativen</h1>
      <p class="max-w-2xl text-sm leading-relaxed text-muted-foreground">
        Fünf Entwürfe für die T-Shirt-Karte im Vergleich zur aktuellen Karte. Die Entwürfe sind nur
        hier eingebunden.
        {{
          usesLiveCatalog
            ? 'Daten: Live-Katalog.'
            : 'Daten: lokale Beispieldaten (Backend nicht erreichbar oder keine T-Shirts).'
        }}
      </p>
      <nav class="flex flex-wrap gap-2" aria-label="Alternativen">
        <Button as-child variant="pill" size="pill">
          <a href="#original">Aktuell</a>
        </Button>
        <Button v-for="alt in alternatives" :key="alt.id" as-child variant="pill" size="pill">
          <a :href="`#${alt.id}`">{{ alt.title }}</a>
        </Button>
      </nav>
    </header>

    <section id="original" class="grid scroll-mt-24 gap-5">
      <div class="grid gap-1">
        <h2 class="text-xl font-bold text-foreground">Aktuell</h2>
        <p class="text-sm text-muted-foreground">Die heutige ProductCard als Referenz.</p>
      </div>
      <div class="grid grid-cols-1 gap-5 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4">
        <ProductCard
          v-for="(article, index) in shirts"
          :key="article.id"
          :article="article"
          :active-variant="originalVariant(article)"
          :price-cents="article.price"
          :regular-price-cents="article.regularPrice"
          :card-index="index"
          @select-variant="originalSelection[article.id] = $event"
        >
          <template #action>
            <Button class="mt-4 w-full">Auswählen</Button>
          </template>
        </ProductCard>
      </div>
    </section>

    <section
      v-for="alt in alternatives"
      :id="alt.id"
      :key="alt.id"
      class="grid scroll-mt-24 gap-5 border-t border-border pt-10"
    >
      <div class="grid gap-1">
        <h2 class="text-xl font-bold text-foreground">{{ alt.title }}</h2>
        <p class="max-w-3xl text-sm text-muted-foreground">{{ alt.description }}</p>
      </div>
      <div class="grid grid-cols-1 gap-5 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4">
        <component
          :is="alt.component"
          v-for="article in shirts"
          :key="article.id"
          :article="article"
        />
      </div>
    </section>
  </div>
</template>
