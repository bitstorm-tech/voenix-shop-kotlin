<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import RoyalDogSectionHeader from './RoyalDogSectionHeader.vue'

const { t } = useI18n()

const steps = ['upload', 'paint', 'mug'] as const
</script>

<template>
  <section id="coronation" class="bg-background-warm py-14">
    <div class="mx-auto max-w-[1100px] px-6">
      <RoyalDogSectionHeader :kicker="t('royalDog.how.kicker')" :title="t('royalDog.how.title')" />
      <div class="grid grid-cols-1 gap-6 md:grid-cols-3">
        <article v-for="(step, index) in steps" :key="step" class="px-4 py-6 text-center">
          <div
            class="step-coin mx-auto mb-3 grid size-9 place-items-center rounded-full font-display text-sm font-black"
          >
            {{ index + 1 }}
          </div>
          <h3 class="font-bold text-foreground">{{ t(`royalDog.how.steps.${step}.title`) }}</h3>
          <p class="mt-1 text-sm leading-relaxed text-foreground-muted">
            {{ t(`royalDog.how.steps.${step}.text`) }}
          </p>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* CSS exception: the gold-coin step markers share the hero frame's metal gradient. */
.step-coin {
  color: oklch(0.28 0.05 282);
  background: linear-gradient(150deg, #ecd08a, #c1913f 55%, #a97c2e);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.55),
    inset 0 -1px 0 rgba(90, 62, 20, 0.45),
    0 2px 6px rgba(24, 20, 40, 0.18);
}
</style>
