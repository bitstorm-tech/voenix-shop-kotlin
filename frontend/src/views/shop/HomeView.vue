<script setup lang="ts">
import GenDesignImage from '@/assets/landing/gen-design.jpg'
import GenPhotoImage from '@/assets/landing/gen-photo.jpg'
import LandingCarousel from '@/components/shop/landing/LandingCarousel.vue'
import LandingCarouselCard from '@/components/shop/landing/LandingCarouselCard.vue'
import ImageComparisonSlider from '@/components/shared/ImageComparisonSlider.vue'
import { Button } from '@/components/ui/button'
import { ArrowRight, BadgeCheck, CheckCircle, ShieldCheck, Truck } from 'lucide-vue-next'
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { usePromptsStore, type PromptDto } from '@/stores/shop/prompts'
import { promptExampleImageUrl } from '@/lib/promptExampleImage'

const { t } = useI18n()
const promptsStore = usePromptsStore()
const categoriesStore = useArticleCategoriesStore()

function styleImageUrl(prompt: PromptDto) {
  return prompt.exampleImageFilename
    ? promptExampleImageUrl(prompt.exampleImageFilename, 400)
    : undefined
}

function styleMeta(prompt: PromptDto) {
  return [prompt.category?.name, prompt.subcategory?.name].filter(Boolean).join(' / ')
}

/** Every subcategory of the shop navigation, which lists only those visible articles use. */
const subcategories = computed(() =>
  categoriesStore.categories.flatMap((category) =>
    category.subcategories.map((subcategory) => ({
      ...subcategory,
      categoryId: category.id,
      categoryName: category.name,
      imageUrl: subcategory.exampleImageFilename
        ? `/api/images/public/400/articles/subcategory-example-images/${subcategory.exampleImageFilename}`
        : undefined,
    })),
  ),
)

const heroMetaItems = ['noAccount', 'preview', 'shipping'] as const

onMounted(() => {
  void promptsStore.fetchPrompts()
  void categoriesStore.fetchCategories()
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

        <div id="products" class="mt-6">
          <LandingCarousel
            :items="subcategories"
            :is-loading="categoriesStore.isLoading"
            :error="categoriesStore.error"
            i18n-key="landing.products"
            @retry="categoriesStore.fetchCategories()"
          >
            <template #item="{ item }">
              <LandingCarouselCard
                :to="{
                  name: 'products',
                  query: { category: String(item.categoryId), subcategory: String(item.id) },
                }"
                :title="item.name"
                :meta="item.categoryName"
                :image-url="item.imageUrl"
                image-loading="eager"
                :aria-label="t('landing.products.openSubcategory', { title: item.name })"
                :no-image-label="t('landing.products.noImage')"
              />
            </template>
          </LandingCarousel>
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

        <div class="mt-4 grid grid-cols-1 gap-4 border-t pt-8 sm:grid-cols-3">
          <div class="text-center">
            <BadgeCheck class="mx-auto mb-2 size-6 text-primary" aria-hidden="true" />
            <p class="text-sm font-bold text-foreground">{{ t('landing.trust.craft.title') }}</p>
            <p class="mt-1 text-xs text-foreground-muted">{{ t('landing.trust.craft.text') }}</p>
          </div>
          <div class="text-center">
            <Truck class="mx-auto mb-2 size-6 text-primary" aria-hidden="true" />
            <p class="text-sm font-bold text-foreground">{{ t('landing.trust.shipping.title') }}</p>
            <p class="mt-1 text-xs text-foreground-muted">{{ t('landing.trust.shipping.text') }}</p>
          </div>
          <div class="text-center">
            <ShieldCheck class="mx-auto mb-2 size-6 text-primary" aria-hidden="true" />
            <p class="text-sm font-bold text-foreground">{{ t('landing.trust.payment.title') }}</p>
            <p class="mt-1 text-xs text-foreground-muted">{{ t('landing.trust.payment.text') }}</p>
          </div>
        </div>
      </div>
    </section>

    <section id="styles" class="py-14">
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

        <LandingCarousel
          :items="promptsStore.prompts"
          :is-loading="promptsStore.isLoading"
          :error="promptsStore.error"
          i18n-key="landing.styles"
          @retry="promptsStore.fetchPrompts()"
        >
          <template #item="{ item }">
            <LandingCarouselCard
              :to="{ name: 'wizard', query: { promptId: String(item.id) } }"
              :title="item.title"
              :meta="styleMeta(item)"
              :image-url="styleImageUrl(item)"
              :aria-label="t('landing.styles.openStyle', { title: item.title })"
              :no-image-label="t('landing.styles.noImage')"
            />
          </template>
        </LandingCarousel>
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
