<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router'
import RoyalDogHero from '@/components/shop/landing/royaldog/RoyalDogHero.vue'
import RoyalDogSteps from '@/components/shop/landing/royaldog/RoyalDogSteps.vue'
import RoyalDogGallery from '@/components/shop/landing/royaldog/RoyalDogGallery.vue'
import RoyalDogReviews from '@/components/shop/landing/royaldog/RoyalDogReviews.vue'
import RoyalDogCtaBand from '@/components/shop/landing/royaldog/RoyalDogCtaBand.vue'

/**
 * The admin-managed "royal portrait" prompt this campaign sells. Set it once the prompt exists;
 * until then the CTA opens the wizard upload-first without a preselected style.
 */
const ROYAL_DOG_PROMPT_ID: number | null = null

/**
 * Campaign renders dropped into assets/landing/royal-dog: `before.*` and `after.*` feed the hero
 * comparison slider, every other image lands in the "freshly crowned" gallery. Missing files
 * degrade gracefully (hero placeholder, hidden gallery).
 */
const campaignImages = import.meta.glob<string>(
  '@/assets/landing/royal-dog/*.{jpg,jpeg,png,webp}',
  {
    eager: true,
    import: 'default',
  },
)

function imageByName(name: string): string | undefined {
  return Object.entries(campaignImages).find(([path]) => path.includes(`/${name}.`))?.[1]
}

const beforeImage = imageByName('before')
const afterImage = imageByName('after')
const galleryImages = Object.entries(campaignImages)
  .filter(([path]) => !path.includes('/before.') && !path.includes('/after.'))
  .sort(([a], [b]) => a.localeCompare(b))
  .map(([, url]) => url)

const wizardTo: RouteLocationRaw = {
  name: 'wizard',
  query: {
    start: 'upload',
    ...(ROYAL_DOG_PROMPT_ID === null ? {} : { promptId: String(ROYAL_DOG_PROMPT_ID) }),
  },
}
</script>

<template>
  <div class="royal-dog bg-background font-sans text-[15px] text-foreground">
    <RoyalDogHero :wizard-to="wizardTo" :before-image="beforeImage" :after-image="afterImage" />
    <RoyalDogSteps />
    <RoyalDogGallery :images="galleryImages" />
    <RoyalDogReviews />
    <RoyalDogCtaBand :wizard-to="wizardTo" />
  </div>
</template>

<style scoped>
/* Campaign accent tokens; the gold flips brighter on dark backgrounds for contrast. */
.royal-dog {
  --royal-gold: oklch(0.58 0.12 78);
  --royal-gold-bright: oklch(0.82 0.13 85);
}

:global(.dark) .royal-dog {
  --royal-gold: oklch(0.78 0.12 82);
}
</style>
