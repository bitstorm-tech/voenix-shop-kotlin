<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { ArrowRight, Award, Mail, MapPin, Sparkles, Truck } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'

interface AboutHighlight {
  id: string
  title: string
  description: string
}

interface AboutContent {
  eyebrow: string
  headline: string
  subheadline: string
  paragraphs: string[]
  claim: string
  highlights: AboutHighlight[]
  ctaTitle: string
  ctaDescription: string
  ctaProducts: string
  ctaContact: string
  email: string
}

const HIGHLIGHT_ICONS = {
  bavaria: MapPin,
  quality: Award,
  shipping: Truck,
} as const

const { tm } = useI18n()

const pageContent = computed(() => tm('footerPages.about.content') as unknown as AboutContent)

function iconFor(id: string) {
  return HIGHLIGHT_ICONS[id as keyof typeof HIGHLIGHT_ICONS] ?? Sparkles
}
</script>

<template>
  <section class="bg-background text-foreground">
    <div class="mx-auto w-full max-w-[1100px] px-6 py-12 sm:px-8 lg:py-20">
      <header class="max-w-3xl space-y-4">
        <div
          class="inline-flex items-center gap-2 text-xs font-bold uppercase tracking-[0.16em] text-primary"
        >
          <Sparkles class="size-4" />
          <span>{{ pageContent.eyebrow }}</span>
        </div>

        <h1 class="text-4xl font-extrabold leading-tight text-foreground sm:text-5xl lg:text-6xl">
          {{ pageContent.headline }}
        </h1>

        <p class="text-xl font-semibold text-primary sm:text-2xl">
          {{ pageContent.subheadline }}
        </p>
      </header>

      <article
        class="relative mt-10 overflow-hidden rounded-lg border border-border bg-card p-6 shadow-sm sm:p-8 lg:p-10"
        data-testid="about-story"
      >
        <div class="absolute left-0 top-0 h-2 w-36 rounded-br-full bg-primary" />

        <div class="max-w-3xl space-y-5 text-base leading-8 text-foreground-soft sm:text-lg">
          <p v-for="paragraph in pageContent.paragraphs" :key="paragraph">
            {{ paragraph }}
          </p>

          <p class="pt-2 text-lg font-bold tracking-tight text-foreground sm:text-xl">
            {{ pageContent.claim }}
          </p>
        </div>
      </article>

      <section class="mt-8 grid gap-4 md:grid-cols-3" data-testid="about-highlights">
        <article
          v-for="highlight in pageContent.highlights"
          :key="highlight.id"
          class="flex flex-col gap-4 rounded-lg border border-border bg-background-soft p-6 shadow-sm"
        >
          <div
            class="inline-flex size-11 items-center justify-center rounded-lg bg-primary/10 text-primary"
          >
            <component :is="iconFor(highlight.id)" class="size-5" />
          </div>

          <div class="space-y-2">
            <h2 class="text-lg font-bold text-foreground">{{ highlight.title }}</h2>
            <p class="text-sm leading-6 text-foreground-muted">{{ highlight.description }}</p>
          </div>
        </article>
      </section>

      <aside
        class="mt-10 rounded-lg border border-border bg-background-soft p-6 shadow-sm sm:p-8"
        data-testid="about-cta"
      >
        <div class="flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
          <div class="space-y-2">
            <h2 class="text-xl font-bold text-foreground">
              {{ pageContent.ctaTitle }}
            </h2>
            <p class="text-sm leading-6 text-foreground-muted">
              {{ pageContent.ctaDescription }}
            </p>
          </div>

          <div class="flex flex-col gap-3 sm:shrink-0 sm:flex-row">
            <Button as-child>
              <RouterLink :to="{ name: 'products' }">
                <span>{{ pageContent.ctaProducts }}</span>
                <ArrowRight class="size-4" />
              </RouterLink>
            </Button>
            <Button as-child variant="outline">
              <a :href="`mailto:${pageContent.email}`">
                <Mail class="size-4" />
                <span>{{ pageContent.ctaContact }}</span>
              </a>
            </Button>
          </div>
        </div>
      </aside>
    </div>
  </section>
</template>
