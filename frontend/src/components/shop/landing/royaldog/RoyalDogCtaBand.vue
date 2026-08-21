<script setup lang="ts">
import { RouterLink, type RouteLocationRaw } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowRight } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import RoyalCrown from './RoyalCrown.vue'

defineProps<{
  wizardTo: RouteLocationRaw
}>()

const { t } = useI18n()
</script>

<template>
  <section class="cta-band py-14 text-center">
    <div class="mx-auto max-w-[1100px] px-6">
      <RoyalCrown class="mx-auto mb-4 h-8 w-12 text-[color:var(--royal-gold-bright)]" />
      <h2
        class="mx-auto max-w-[24ch] font-display text-2xl font-extrabold tracking-normal text-[oklch(0.96_0.02_85)] md:text-3xl"
      >
        {{ t('royalDog.cta.title') }}
      </h2>
      <p class="mx-auto mt-2 max-w-[520px] text-sm text-[oklch(0.78_0.02_85)]">
        {{ t('royalDog.cta.subtitle') }}
      </p>
      <Button as-child variant="shop" size="shop" class="mt-7">
        <RouterLink :to="wizardTo">
          {{ t('royalDog.cta.primaryCta') }}
          <ArrowRight aria-hidden="true" />
        </RouterLink>
      </Button>
    </div>
  </section>
</template>

<style scoped>
/* CSS exception: the throne-room band keeps its deep ink look in both themes. */
.cta-band {
  background:
    radial-gradient(circle at 50% 0%, rgba(233, 196, 106, 0.14), transparent 55%),
    linear-gradient(170deg, oklch(0.29 0.055 282), oklch(0.21 0.045 282));
}
</style>
