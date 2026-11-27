<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { ArrowRight, ChevronDown, HelpCircle, Mail } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'

interface FaqItem {
  id: string
  question: string
  paragraphs: string[]
  linkLabel: string
}

interface FaqContent {
  eyebrow: string
  introPrefix: string
  introSuffix: string
  email: string
  items: FaqItem[]
  ctaTitle: string
  ctaDescription: string
  ctaHome: string
  ctaEmail: string
}

const ITEM_ROUTES: Record<string, string> = {
  returns: 'returns',
  shippingCountries: 'shipping',
  payment: 'payment',
}

const { t, tm } = useI18n()

const pageContent = computed(() => tm('footerPages.faq.content') as unknown as FaqContent)

function routeNameFor(id: string): string | undefined {
  return ITEM_ROUTES[id]
}
</script>

<template>
  <section class="bg-background text-foreground">
    <div class="mx-auto w-full max-w-4xl px-6 py-12 sm:px-8 lg:py-20">
      <header class="mb-10 max-w-3xl space-y-4">
        <div
          class="inline-flex items-center gap-2 text-xs font-bold uppercase tracking-[0.16em] text-primary"
        >
          <HelpCircle class="size-4" />
          <span>{{ pageContent.eyebrow }}</span>
        </div>

        <h1 class="text-4xl font-extrabold leading-tight text-foreground sm:text-5xl">
          {{ t('footerPages.faq.title') }}
        </h1>

        <p class="text-base leading-7 text-foreground-muted">
          {{ t('footerPages.faq.description') }}
        </p>

        <p class="text-sm leading-6 text-foreground-muted">
          {{ pageContent.introPrefix }}
          <a
            :href="`mailto:${pageContent.email}`"
            class="font-semibold text-primary underline hover:opacity-80"
          >
            {{ pageContent.email }}
          </a>
          {{ pageContent.introSuffix }}
        </p>
      </header>

      <div class="space-y-3" data-testid="faq-list">
        <Collapsible
          v-for="(item, index) in pageContent.items"
          :key="item.id"
          v-slot="{ open }"
          :default-open="index === 0"
          class="rounded-lg border border-border bg-card shadow-sm transition-colors hover:border-primary/40"
          :data-testid="`faq-item-${item.id}`"
        >
          <CollapsibleTrigger
            class="flex w-full items-center justify-between gap-4 px-5 py-4 text-left text-base font-semibold text-foreground sm:px-6 sm:py-5"
          >
            <span>{{ item.question }}</span>
            <ChevronDown
              class="size-5 shrink-0 text-foreground-muted transition-transform duration-200"
              :class="{ 'rotate-180 text-primary': open }"
            />
          </CollapsibleTrigger>

          <CollapsibleContent class="border-t border-border px-5 pb-5 pt-4 sm:px-6 sm:pb-6">
            <div class="space-y-3 text-base leading-7 text-foreground-soft">
              <p v-for="paragraph in item.paragraphs" :key="paragraph">
                {{ paragraph }}
              </p>

              <div v-if="routeNameFor(item.id) && item.linkLabel" class="pt-1">
                <RouterLink
                  :to="{ name: routeNameFor(item.id) }"
                  class="inline-flex items-center gap-1.5 text-sm font-semibold text-primary underline underline-offset-4 hover:opacity-80"
                >
                  <span>{{ item.linkLabel }}</span>
                  <ArrowRight class="size-4" />
                </RouterLink>
              </div>
            </div>
          </CollapsibleContent>
        </Collapsible>
      </div>

      <aside
        class="mt-10 rounded-lg border border-border bg-background-soft p-6 shadow-sm sm:p-8"
        data-testid="faq-cta"
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
            <Button as-child variant="outline">
              <RouterLink :to="{ name: 'home' }">
                {{ pageContent.ctaHome }}
              </RouterLink>
            </Button>
            <Button as-child>
              <a :href="`mailto:${pageContent.email}`">
                <Mail class="size-4" />
                <span>{{ pageContent.ctaEmail }}</span>
              </a>
            </Button>
          </div>
        </div>
      </aside>
    </div>
  </section>
</template>
