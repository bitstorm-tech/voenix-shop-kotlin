<script setup lang="ts">
import GenCardImage from '@/assets/landing/gen-card.jpg'
import GenDesignImage from '@/assets/landing/gen-design.jpg'
import GenPhotoImage from '@/assets/landing/gen-photo.jpg'
import GenPosterImage from '@/assets/landing/gen-poster.jpg'
import GenThermoImage from '@/assets/landing/gen-thermo.jpg'
import GenTshirtImage from '@/assets/landing/gen-tshirt.jpg'
import GenTshirt2Image from '@/assets/landing/gen-tshirt2.jpg'
import LandingStylesCarousel from '@/components/shop/landing/LandingStylesCarousel.vue'
import ImageComparisonSlider from '@/components/shared/ImageComparisonSlider.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import Mug2Image from '@/assets/landing/mug2.png'
import Mug3Image from '@/assets/landing/mug3.png'
import { ArrowRight, BadgeCheck, CheckCircle, ShieldCheck, Star, Truck } from 'lucide-vue-next'
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { usePromptsStore } from '@/stores/shop/prompts'

const { t } = useI18n()
const promptsStore = usePromptsStore()

interface ProductItem {
  key: 'ceramic' | 'thermo' | 'poster' | 'shirt'
  thumbClass: string
  imageClass: string
  to?: string
  soon?: boolean
  image: string
  imageWidth: number
  imageHeight: number
}

const productItems: ProductItem[] = [
  {
    key: 'ceramic',
    thumbClass: 'bg-background-warm p-2',
    imageClass: 'object-contain',
    to: '/mugs',
    image: Mug3Image,
    imageWidth: 494,
    imageHeight: 651,
  },
  {
    key: 'thermo',
    thumbClass: 'bg-background-soft p-2',
    imageClass: 'object-contain rounded-md',
    to: '/mugs',
    image: GenThermoImage,
    imageWidth: 512,
    imageHeight: 512,
  },
  {
    key: 'poster',
    thumbClass: 'bg-muted p-2',
    imageClass: 'object-contain rounded-md',
    to: '/wizard',
    image: GenPosterImage,
    imageWidth: 512,
    imageHeight: 512,
  },
  {
    key: 'shirt',
    thumbClass: 'bg-background-soft',
    imageClass: 'object-cover',
    soon: true,
    image: GenTshirt2Image,
    imageWidth: 512,
    imageHeight: 512,
  },
]

const reviews = ['markus', 'sarah', 'julia'] as const
const heroMetaItems = ['noAccount', 'preview', 'shipping'] as const

onMounted(() => {
  void promptsStore.fetchPrompts()
})
</script>

<template>
  <div class="bg-background font-sans text-[15px] text-foreground">
    <section class="py-12">
      <div class="mx-auto max-w-[1100px] px-6">
        <div class="grid grid-cols-1 items-center gap-8 md:grid-cols-2 md:gap-12">
          <div class="text-center md:text-left">
            <h1
              class="mx-auto max-w-[22ch] text-3xl font-extrabold leading-tight tracking-normal text-foreground md:mx-0 md:text-5xl"
            >
              {{ t('landing.hero.title') }}
            </h1>
            <p class="mx-auto mt-3 max-w-[420px] leading-relaxed text-foreground-muted md:mx-0">
              {{ t('landing.hero.subtitle') }}
            </p>
            <div class="mt-6 flex flex-wrap items-center justify-center gap-4 md:justify-start">
              <Button as-child variant="shop" size="shop">
                <RouterLink to="/wizard?start=upload">
                  {{ t('landing.hero.primaryCta') }}
                  <ArrowRight aria-hidden="true" />
                </RouterLink>
              </Button>
              <a
                href="#how"
                class="text-sm font-semibold text-foreground-muted underline decoration-border underline-offset-4 transition-colors hover:text-foreground"
              >
                {{ t('landing.hero.anchorHow') }}
              </a>
            </div>
            <div
              class="mt-6 flex flex-wrap justify-center gap-x-5 gap-y-2 text-xs font-medium text-foreground-faint md:justify-start"
            >
              <span
                v-for="item in heroMetaItems"
                :key="item"
                class="inline-flex items-center gap-1.5"
              >
                <CheckCircle class="size-3.5 text-primary" aria-hidden="true" />
                {{ t(`landing.hero.meta.${item}`) }}
              </span>
            </div>
          </div>

          <div class="mx-auto w-full max-w-[430px] md:mx-0 md:justify-self-end">
            <ImageComparisonSlider
              :before-image="GenPhotoImage"
              :after-image="GenDesignImage"
              :before-alt="t('landing.hero.stages.photo.label')"
              :after-alt="t('landing.hero.stages.design.label')"
              :before-label="t('landing.hero.stages.photo.label')"
              :after-label="t('landing.hero.stages.design.label')"
              :after-tag="t('landing.hero.stages.design.tag')"
              :hint-label="t('landing.hero.comparison.hint')"
              :slider-aria-label="t('landing.hero.comparison.ariaLabel')"
            />
          </div>
        </div>

        <div class="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Card as="article" class="overflow-hidden rounded-lg bg-background-soft">
            <div
              class="flex aspect-[4/3] items-center justify-center overflow-hidden bg-background-warm p-2"
            >
              <img
                :src="Mug2Image"
                :alt="t('landing.categories.mug.label')"
                class="size-full object-contain"
                width="553"
                height="630"
                loading="eager"
                decoding="async"
              />
            </div>
            <div
              class="flex items-center justify-between gap-2 border-t px-3 py-2 text-xs font-bold text-foreground-soft"
            >
              <span>{{ t('landing.categories.mug.label') }}</span>
              <Badge class="rounded-sm bg-primary px-2 py-0.5 text-[10px] text-primary-foreground">
                {{ t('landing.categories.mug.tag') }}
              </Badge>
            </div>
          </Card>
          <Card as="article" class="overflow-hidden rounded-lg bg-background-soft">
            <div class="flex aspect-[4/3] items-center justify-center overflow-hidden bg-muted">
              <img
                :src="GenCardImage"
                :alt="t('landing.categories.card.label')"
                class="size-full object-cover"
                width="512"
                height="512"
                loading="eager"
                decoding="async"
              />
            </div>
            <div
              class="flex items-center justify-between gap-2 border-t px-3 py-2 text-xs font-bold text-foreground-soft"
            >
              <span>{{ t('landing.categories.card.label') }}</span>
              <Badge class="rounded-sm bg-primary px-2 py-0.5 text-[10px] text-primary-foreground">
                {{ t('landing.categories.card.tag') }}
              </Badge>
            </div>
          </Card>
          <Card as="article" class="overflow-hidden rounded-lg bg-background-soft">
            <div
              class="flex aspect-[4/3] items-center justify-center overflow-hidden bg-background-soft"
            >
              <img
                :src="GenTshirtImage"
                :alt="t('landing.categories.shirt.label')"
                class="size-full object-cover"
                width="512"
                height="512"
                loading="eager"
                decoding="async"
              />
            </div>
            <div
              class="flex items-center justify-between gap-2 border-t px-3 py-2 text-xs font-bold text-foreground-soft"
            >
              <span>{{ t('landing.categories.shirt.label') }}</span>
              <Badge
                class="rounded-sm bg-surface-inverse px-2 py-0.5 text-[10px] text-surface-inverse-foreground"
              >
                {{ t('landing.categories.shirt.tag') }}
              </Badge>
            </div>
          </Card>
        </div>
      </div>
    </section>

    <section id="how" class="bg-background-warm py-14">
      <div class="mx-auto max-w-[1100px] px-6">
        <header class="mx-auto mb-8 max-w-[480px] text-center">
          <p class="mb-2 text-xs font-bold uppercase tracking-[0.1em] text-primary">
            {{ t('landing.how.kicker') }}
          </p>
          <h2 class="text-2xl font-extrabold tracking-normal text-foreground">
            {{ t('landing.how.title') }}
          </h2>
        </header>
        <div class="grid grid-cols-1 gap-6 md:grid-cols-3">
          <article class="px-4 py-6 text-center">
            <div
              class="mx-auto mb-3 grid size-8 place-items-center rounded-full bg-primary text-sm font-extrabold text-primary-foreground"
            >
              1
            </div>
            <h3 class="font-bold text-foreground">{{ t('landing.how.steps.upload.title') }}</h3>
            <p class="mt-1 text-sm leading-relaxed text-foreground-muted">
              {{ t('landing.how.steps.upload.text') }}
            </p>
          </article>
          <article class="px-4 py-6 text-center">
            <div
              class="mx-auto mb-3 grid size-8 place-items-center rounded-full bg-primary text-sm font-extrabold text-primary-foreground"
            >
              2
            </div>
            <h3 class="font-bold text-foreground">{{ t('landing.how.steps.style.title') }}</h3>
            <p class="mt-1 text-sm leading-relaxed text-foreground-muted">
              {{ t('landing.how.steps.style.text') }}
            </p>
          </article>
          <article class="px-4 py-6 text-center">
            <div
              class="mx-auto mb-3 grid size-8 place-items-center rounded-full bg-primary text-sm font-extrabold text-primary-foreground"
            >
              3
            </div>
            <h3 class="font-bold text-foreground">{{ t('landing.how.steps.checkout.title') }}</h3>
            <p class="mt-1 text-sm leading-relaxed text-foreground-muted">
              {{ t('landing.how.steps.checkout.text') }}
            </p>
          </article>
        </div>
      </div>
    </section>

    <section id="products" class="py-14">
      <div class="mx-auto max-w-[1100px] px-6">
        <header class="mx-auto mb-8 max-w-[480px] text-center">
          <p class="mb-2 text-xs font-bold uppercase tracking-[0.1em] text-primary">
            {{ t('landing.products.kicker') }}
          </p>
          <h2 class="text-2xl font-extrabold tracking-normal text-foreground">
            {{ t('landing.products.title') }}
          </h2>
          <p class="mt-2 text-sm leading-relaxed text-foreground-muted">
            {{ t('landing.products.subtitle') }}
          </p>
        </header>

        <div class="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <component
            :is="item.to && !item.soon ? RouterLink : 'article'"
            v-for="item in productItems"
            :key="item.key"
            v-bind="item.to && !item.soon ? { to: item.to } : {}"
            class="group block overflow-hidden rounded-lg border bg-card text-inherit no-underline shadow-sm transition-all duration-200"
            :class="
              item.to && !item.soon
                ? 'hover:-translate-y-0.5 hover:shadow-[var(--shadow-elevated-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2'
                : ''
            "
          >
            <div
              class="relative flex aspect-square items-center justify-center overflow-hidden"
              :class="item.thumbClass"
            >
              <img
                :src="item.image"
                :alt="t(`landing.products.items.${item.key}.title`)"
                class="block size-full"
                :class="item.imageClass"
                :width="item.imageWidth"
                :height="item.imageHeight"
                loading="lazy"
                decoding="async"
              />
              <Badge
                v-if="item.soon"
                class="absolute right-2 top-2 rounded bg-surface-inverse px-2 py-0.5 text-[10px] text-surface-inverse-foreground"
              >
                {{ t('landing.products.soon') }}
              </Badge>
            </div>
            <div class="p-3">
              <h3 class="text-sm font-bold text-foreground">
                {{ t(`landing.products.items.${item.key}.title`) }}
              </h3>
              <p class="mt-1 text-xs text-foreground-muted">
                {{ t(`landing.products.items.${item.key}.text`) }}
              </p>
              <p class="mt-2 text-sm font-bold text-primary">
                {{ t(`landing.products.items.${item.key}.price`) }}
                <span class="font-normal text-foreground-faint">
                  {{ t('landing.products.items.taxIncluded') }}
                </span>
              </p>
            </div>
          </component>
        </div>

        <div class="mt-10 grid grid-cols-1 gap-4 border-y py-8 sm:grid-cols-3">
          <div class="text-center">
            <BadgeCheck class="mx-auto mb-2 size-6 text-primary" aria-hidden="true" />
            <p class="text-sm font-bold text-foreground">{{ t('landing.trust.craft.title') }}</p>
            <p class="mt-1 text-xs text-foreground-faint">{{ t('landing.trust.craft.text') }}</p>
          </div>
          <div class="text-center">
            <Truck class="mx-auto mb-2 size-6 text-primary" aria-hidden="true" />
            <p class="text-sm font-bold text-foreground">{{ t('landing.trust.shipping.title') }}</p>
            <p class="mt-1 text-xs text-foreground-faint">{{ t('landing.trust.shipping.text') }}</p>
          </div>
          <div class="text-center">
            <ShieldCheck class="mx-auto mb-2 size-6 text-primary" aria-hidden="true" />
            <p class="text-sm font-bold text-foreground">{{ t('landing.trust.payment.title') }}</p>
            <p class="mt-1 text-xs text-foreground-faint">{{ t('landing.trust.payment.text') }}</p>
          </div>
        </div>
      </div>
    </section>

    <section id="styles" class="bg-background-soft py-14">
      <div class="mx-auto max-w-[1100px] px-6">
        <header class="mx-auto mb-8 max-w-[480px] text-center">
          <p class="mb-2 text-xs font-bold uppercase tracking-[0.1em] text-primary">
            {{ t('landing.styles.kicker') }}
          </p>
          <h2 class="text-2xl font-extrabold tracking-normal text-foreground">
            {{ t('landing.styles.title') }}
          </h2>
          <p class="mt-2 text-sm leading-relaxed text-foreground-muted">
            {{ t('landing.styles.subtitle') }}
          </p>
        </header>

        <LandingStylesCarousel
          :prompts="promptsStore.sortedPrompts"
          :is-loading="promptsStore.isLoading"
          :error="promptsStore.error"
          :get-image-url="promptsStore.getExampleImageUrl"
          @retry="promptsStore.fetchPrompts()"
        />
      </div>
    </section>

    <section class="py-14">
      <div class="mx-auto max-w-[1100px] px-6">
        <header class="mx-auto mb-8 max-w-[480px] text-center">
          <p class="mb-2 text-xs font-bold uppercase tracking-[0.1em] text-primary">
            {{ t('landing.reviews.kicker') }}
          </p>
          <h2 class="text-2xl font-extrabold tracking-normal text-foreground">
            {{ t('landing.reviews.title') }}
          </h2>
        </header>

        <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
          <Card
            v-for="review in reviews"
            :key="review"
            as="article"
            class="rounded-lg bg-background-soft p-5"
          >
            <div class="mb-2 flex gap-0.5 text-rating">
              <Star v-for="index in 5" :key="index" class="size-3.5 fill-current" />
            </div>
            <p class="text-sm leading-relaxed text-foreground-soft">
              {{ t(`landing.reviews.items.${review}.text`) }}
            </p>
            <div class="mt-4 flex items-center gap-2">
              <div
                class="grid size-7 place-items-center rounded-full bg-background-warm text-xs font-bold text-foreground-soft"
              >
                {{ t(`landing.reviews.items.${review}.initial`) }}
              </div>
              <div>
                <p class="text-xs font-bold text-foreground">
                  {{ t(`landing.reviews.items.${review}.name`) }}
                </p>
                <p class="text-[11px] text-foreground-faint">
                  {{ t(`landing.reviews.items.${review}.detail`) }}
                </p>
              </div>
            </div>
          </Card>
        </div>
      </div>
    </section>

    <section class="bg-surface-inverse py-12 text-center">
      <div class="mx-auto max-w-[1100px] px-6">
        <h2 class="text-2xl font-extrabold tracking-normal text-surface-inverse-foreground">
          {{ t('landing.cta.title') }}
        </h2>
        <p class="mx-auto mt-2 max-w-[520px] text-sm text-surface-inverse-foreground-muted">
          {{ t('landing.cta.subtitle') }}
        </p>
        <Button as-child variant="shop" size="shop" class="mt-6">
          <RouterLink to="/wizard?start=upload">
            {{ t('landing.cta.primaryCta') }}
            <ArrowRight aria-hidden="true" />
          </RouterLink>
        </Button>
      </div>
    </section>
  </div>
</template>
