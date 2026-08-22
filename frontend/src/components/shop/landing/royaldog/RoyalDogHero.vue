<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, type RouteLocationRaw } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowRight, CheckCircle } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import ImageComparisonSlider from '@/components/shared/ImageComparisonSlider.vue'
import RoyalCrown from './RoyalCrown.vue'

const props = defineProps<{
  wizardTo: RouteLocationRaw
  /** Campaign photo of the dog before the coronation; falls back to a placeholder when missing. */
  beforeImage?: string
  /** The generated royal portrait matching [beforeImage]. */
  afterImage?: string
}>()

const { t } = useI18n()

const metaItems = ['noAccount', 'preview', 'shipping'] as const

const hasPortraitPair = computed(
  () => props.beforeImage !== undefined && props.afterImage !== undefined,
)
</script>

<template>
  <section class="py-12 md:py-16">
    <div class="mx-auto max-w-[1100px] px-6">
      <div class="grid grid-cols-1 items-center gap-10 md:grid-cols-2 md:gap-12">
        <div class="text-center md:text-left">
          <p
            class="mb-3 text-xs font-bold uppercase tracking-[0.18em] text-[color:var(--royal-gold)]"
          >
            {{ t('royalDog.hero.eyebrow') }}
          </p>
          <h1
            class="mx-auto max-w-[16ch] font-display text-4xl font-black leading-[1.08] tracking-normal text-foreground md:mx-0 md:text-6xl"
          >
            {{ t('royalDog.hero.title') }}
          </h1>
          <p class="mx-auto mt-4 max-w-[420px] leading-relaxed text-foreground-muted md:mx-0">
            {{ t('royalDog.hero.subtitle') }}
          </p>
          <div class="mt-7 flex flex-wrap items-center justify-center gap-4 md:justify-start">
            <Button as-child variant="shop" size="shop">
              <RouterLink :to="wizardTo">
                {{ t('royalDog.hero.primaryCta') }}
                <ArrowRight aria-hidden="true" />
              </RouterLink>
            </Button>
            <a
              href="#coronation"
              class="text-sm font-semibold text-foreground-muted underline decoration-border underline-offset-4 transition-colors hover:text-foreground"
            >
              {{ t('royalDog.hero.anchorHow') }}
            </a>
          </div>
          <div
            class="mt-6 flex flex-wrap justify-center gap-x-5 gap-y-2 text-xs font-medium text-foreground-faint md:justify-start"
          >
            <span v-for="item in metaItems" :key="item" class="inline-flex items-center gap-1.5">
              <CheckCircle class="size-3.5 text-[color:var(--royal-gold)]" aria-hidden="true" />
              {{ t(`royalDog.hero.meta.${item}`) }}
            </span>
          </div>
        </div>

        <div class="mx-auto w-full max-w-[430px] md:mx-0 md:justify-self-end">
          <div class="royal-frame relative">
            <RoyalCrown
              class="absolute left-1/2 top-0 z-10 h-9 w-14 -translate-x-1/2 -translate-y-[68%] text-[color:var(--royal-gold-bright)] drop-shadow-[0_3px_6px_rgba(24,20,40,0.35)]"
            />
            <div class="royal-frame__inner">
              <ImageComparisonSlider
                v-if="hasPortraitPair && beforeImage && afterImage"
                :before-image="beforeImage"
                :after-image="afterImage"
                :before-alt="t('royalDog.hero.stages.before.label')"
                :after-alt="t('royalDog.hero.stages.after.label')"
                :before-label="t('royalDog.hero.stages.before.label')"
                :after-label="t('royalDog.hero.stages.after.label')"
                :after-tag="t('royalDog.hero.stages.after.tag')"
                :hint-label="t('royalDog.hero.comparison.hint')"
                :slider-aria-label="t('royalDog.hero.comparison.ariaLabel')"
              />
              <div
                v-else
                class="royal-frame__placeholder flex aspect-square flex-col items-center justify-center gap-4 p-8 text-center"
              >
                <RoyalCrown class="h-14 w-20 text-[color:var(--royal-gold-bright)]" />
                <p class="font-display text-xl font-extrabold text-[oklch(0.94_0.03_85)]">
                  {{ t('royalDog.hero.placeholder.title') }}
                </p>
                <p class="text-sm text-[oklch(0.78_0.02_85)]">
                  {{ t('royalDog.hero.placeholder.note') }}
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* CSS exception: the gilded frame needs layered gradients no utility class expresses. */
.royal-frame {
  padding: 0.85rem;
  border-radius: 0.9rem;
  background: linear-gradient(150deg, #ecd08a, #b8873b 34%, #f4e0a4 52%, #9c7127 78%, #d9b45c);
  box-shadow:
    0 24px 50px rgba(24, 20, 40, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.55),
    inset 0 -1px 0 rgba(90, 62, 20, 0.5);
}

.royal-frame__inner {
  overflow: hidden;
  border: 1px solid rgba(90, 62, 20, 0.55);
  border-radius: 0.45rem;
}

.royal-frame__placeholder {
  background:
    radial-gradient(circle at 50% 30%, rgba(233, 196, 106, 0.16), transparent 60%),
    linear-gradient(165deg, oklch(0.3 0.055 282), oklch(0.22 0.045 282));
}
</style>
