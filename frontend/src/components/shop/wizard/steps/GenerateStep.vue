<script setup lang="ts">
import { shallowRef, watch, computed, onBeforeUnmount, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Sparkles, RefreshCw, AlertCircle, ZoomIn, Loader2 } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { useWizardStore } from '@/stores/shop/wizard'
import { useImageGenerationStore } from '@/stores/shop/imageGeneration'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'
import {
  IMAGE_GENERATION_MAGIC_COIN_COST,
  INSUFFICIENT_MAGIC_COINS_CODE,
  MAGIC_COINS_ROUTE,
} from '@/lib/magicCoins'
import VariantGallery from '@/components/shop/wizard/VariantGallery.vue'
import { useGenerationErrorMessage } from '@/composables/useGenerationErrorMessage'

const { t } = useI18n()
const wizard = useWizardStore()
const imageGeneration = useImageGenerationStore()
const magicCoinsStore = useMagicCoinsStore()
const lightboxOpen = shallowRef(false)

// --- Timer ---
const elapsedSeconds = shallowRef(0)
let timerInterval: ReturnType<typeof setInterval> | null = null

const formattedTime = computed(() => {
  const mins = Math.floor(elapsedSeconds.value / 60)
  const secs = elapsedSeconds.value % 60
  return `${mins}:${String(secs).padStart(2, '0')}`
})

// --- Message rotation ---
const messageKeys = [
  'mugConfigurator.steps.generate.generatingMessages.analyzing',
  'mugConfigurator.steps.generate.generatingMessages.creating',
  'mugConfigurator.steps.generate.generatingMessages.styling',
  'mugConfigurator.steps.generate.generatingMessages.refining',
  'mugConfigurator.steps.generate.generatingMessages.almostThere',
] as const

const currentMessageIndex = shallowRef(0)
let messageInterval: ReturnType<typeof setInterval> | null = null

const currentMessage = computed(() => t(messageKeys[currentMessageIndex.value]!))
const canGenerate = computed(() => magicCoinsStore.balance !== null && magicCoinsStore.balance > 0)
const isCheckingMagicCoins = computed(
  () => magicCoinsStore.balance === null && magicCoinsStore.isLoading,
)
const hasMagicCoinsError = computed(
  () => magicCoinsStore.balance === null && magicCoinsStore.error !== null,
)
const shouldShowInsufficientMagicCoins = computed(
  () =>
    imageGeneration.errorCode === INSUFFICIENT_MAGIC_COINS_CODE ||
    (imageGeneration.errorStatus === null &&
      !imageGeneration.hasImages &&
      magicCoinsStore.balance !== null &&
      magicCoinsStore.balance <= 0),
)

const generationErrorMessage = useGenerationErrorMessage()

function startAnimations() {
  elapsedSeconds.value = 0
  currentMessageIndex.value = 0

  timerInterval = setInterval(() => {
    elapsedSeconds.value++
  }, 1000)

  const lastIndex = messageKeys.length - 1
  messageInterval = setInterval(() => {
    if (elapsedSeconds.value >= 20) {
      // Stay on "Almost there..."
      currentMessageIndex.value = lastIndex
    } else {
      // Loop through indices 0-(lastIndex-1), then 1-(lastIndex-1) (skip "Analyzing" after first pass)
      const next = currentMessageIndex.value + 1
      currentMessageIndex.value = next >= lastIndex ? 1 : next
    }
  }, 4000)
}

function stopAnimations() {
  if (timerInterval) {
    clearInterval(timerInterval)
    timerInterval = null
  }
  if (messageInterval) {
    clearInterval(messageInterval)
    messageInterval = null
  }
}

watch(
  () => imageGeneration.isGenerating,
  (generating) => {
    if (generating) {
      startAnimations()
    } else {
      stopAnimations()
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  stopAnimations()
})

async function generate() {
  if (!wizard.imageForGeneration || !wizard.selectedPromptId) return

  if (magicCoinsStore.balance === null) {
    await magicCoinsStore.fetchBalance()
  }

  if (!canGenerate.value) return

  await imageGeneration.generateImage(wizard.imageForGeneration, wizard.selectedPromptId)
}

onMounted(async () => {
  if (
    imageGeneration.hasImages ||
    imageGeneration.isGenerating ||
    !wizard.imageForGeneration ||
    !wizard.selectedPromptId
  ) {
    return
  }

  await generate()
})
</script>

<template>
  <div class="wizard-step-enter pb-2">
    <h2 class="sr-only">{{ t('mugConfigurator.steps.generate.title') }}</h2>

    <!-- Loading state -->
    <div v-if="imageGeneration.isGenerating" class="mt-6 sm:mt-8">
      <div
        class="overflow-hidden rounded-xl border border-border bg-card shadow-[0_1px_3px_oklch(0_0_0_/_0.06),0_4px_12px_oklch(0_0_0_/_0.04)] motion-safe:animate-wizard-step-enter motion-reduce:animate-none"
      >
        <div
          role="status"
          class="relative flex flex-col items-center justify-center overflow-hidden bg-[oklch(0.15_0.04_280)] px-6 py-16 sm:px-10 sm:py-20"
        >
          <!-- Aurora gradients -->
          <div class="generate-aurora-1 pointer-events-none absolute inset-0" aria-hidden="true" />
          <div class="generate-aurora-2 pointer-events-none absolute inset-0" aria-hidden="true" />

          <!-- Shimmer sweep -->
          <div class="generate-shimmer pointer-events-none absolute inset-0" aria-hidden="true" />

          <!-- Orb system -->
          <div class="relative h-[200px] w-[200px] sm:h-[240px] sm:w-[240px]" aria-hidden="true">
            <!-- Bloom -->
            <div class="generate-bloom absolute inset-[-40px] rounded-full sm:inset-[-50px]" />

            <!-- Outer ring -->
            <div class="generate-ring-outer absolute inset-[-14px] rounded-full sm:inset-[-18px]" />

            <!-- Middle ring -->
            <div class="generate-ring-middle absolute inset-[14px] rounded-full sm:inset-[18px]" />

            <!-- Inner ring -->
            <div class="generate-ring-inner absolute inset-[36px] rounded-full sm:inset-[44px]" />

            <!-- Far orbit -->
            <div class="generate-orbit-far absolute inset-0">
              <div
                v-for="i in 6"
                :key="'f' + i"
                class="generate-particle-far absolute left-1/2 top-1/2"
                :style="{ transform: `rotate(${i * 60}deg) translateY(-92px)` }"
              />
            </div>

            <!-- Near orbit -->
            <div class="generate-orbit-near absolute inset-0">
              <div
                v-for="i in 4"
                :key="'n' + i"
                class="generate-particle-near absolute left-1/2 top-1/2"
                :style="{ transform: `rotate(${i * 90 + 45}deg) translateY(-56px)` }"
              />
            </div>

            <!-- Center orb -->
            <div
              class="generate-center-orb absolute left-1/2 top-1/2 flex h-20 w-20 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full sm:h-24 sm:w-24"
            >
              <Sparkles class="generate-icon-pulse h-8 w-8 text-white sm:h-10 sm:w-10" />
            </div>
          </div>

          <!-- Status message -->
          <div aria-live="polite" class="mt-8 h-8 overflow-hidden sm:mt-10">
            <Transition
              mode="out-in"
              enter-active-class="transition-[opacity,transform] duration-300 ease-out motion-reduce:transition-none"
              leave-active-class="transition-[opacity,transform] duration-300 ease-in motion-reduce:transition-none"
              enter-from-class="translate-y-2 opacity-0 motion-reduce:translate-y-0"
              leave-to-class="-translate-y-2 opacity-0 motion-reduce:translate-y-0"
            >
              <p
                :key="currentMessageIndex"
                class="text-base font-semibold tracking-tight text-[oklch(0.9_0.03_280)] sm:text-lg"
              >
                {{ currentMessage }}
              </p>
            </Transition>
          </div>

          <!-- Timer -->
          <p aria-live="off" class="mt-2 text-sm tabular-nums tracking-wide text-white/30">
            {{ formattedTime }}
          </p>

          <!-- Progress bar -->
          <div
            class="mt-6 h-1.5 w-full max-w-xs overflow-hidden rounded-full bg-white/10 sm:mt-8 sm:max-w-sm"
            aria-hidden="true"
          >
            <div class="generate-progress-bar h-full rounded-full" />
          </div>
        </div>
      </div>
    </div>

    <!-- Magic Coins loading state -->
    <div v-else-if="isCheckingMagicCoins" class="mt-6 sm:mt-8">
      <div
        class="overflow-hidden rounded-xl border border-border bg-card shadow-[0_1px_3px_oklch(0_0_0_/_0.06),0_4px_12px_oklch(0_0_0_/_0.04)] motion-safe:animate-wizard-step-enter motion-reduce:animate-none"
      >
        <div class="flex flex-col items-center justify-center gap-5 p-10 sm:p-14">
          <div
            class="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10 sm:h-[4.5rem] sm:w-[4.5rem]"
          >
            <Loader2 class="h-6 w-6 animate-spin text-primary sm:h-7 sm:w-7" />
          </div>
          <p class="text-center text-sm text-muted-foreground sm:text-base">
            {{ t('mugConfigurator.steps.generate.checkingMagicCoins') }}
          </p>
        </div>
      </div>
    </div>

    <!-- Insufficient Magic Coins state -->
    <div v-else-if="shouldShowInsufficientMagicCoins" class="mt-6 sm:mt-8">
      <div
        class="overflow-hidden rounded-xl border border-border bg-card shadow-[0_1px_3px_oklch(0_0_0_/_0.06),0_4px_12px_oklch(0_0_0_/_0.04)] motion-safe:animate-wizard-step-enter motion-reduce:animate-none"
      >
        <div
          class="flex flex-col items-center justify-center gap-5 bg-[linear-gradient(180deg,oklch(0.98_0.01_20_/_0.3)_0%,oklch(0.99_0.005_20_/_0.2)_100%)] p-10 sm:p-14"
        >
          <div
            class="flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10 sm:h-[4.5rem] sm:w-[4.5rem]"
          >
            <AlertCircle class="h-6 w-6 text-destructive sm:h-7 sm:w-7" />
          </div>
          <p class="text-center text-sm text-muted-foreground sm:text-base">
            {{ t('mugConfigurator.steps.generate.insufficientMagicCoins') }}
          </p>
          <Button as-child variant="outline" size="sm">
            <RouterLink :to="MAGIC_COINS_ROUTE">
              {{ t('mugConfigurator.steps.generate.refillMagicCoins') }}
            </RouterLink>
          </Button>
        </div>
      </div>
    </div>

    <!-- Magic Coins unavailable state -->
    <div v-else-if="hasMagicCoinsError" class="mt-6 sm:mt-8">
      <div
        class="overflow-hidden rounded-xl border border-border bg-card shadow-[0_1px_3px_oklch(0_0_0_/_0.06),0_4px_12px_oklch(0_0_0_/_0.04)] motion-safe:animate-wizard-step-enter motion-reduce:animate-none"
      >
        <div
          class="flex flex-col items-center justify-center gap-5 bg-[linear-gradient(180deg,oklch(0.98_0.01_20_/_0.3)_0%,oklch(0.99_0.005_20_/_0.2)_100%)] p-10 sm:p-14"
        >
          <div
            class="flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10 sm:h-[4.5rem] sm:w-[4.5rem]"
          >
            <AlertCircle class="h-6 w-6 text-destructive sm:h-7 sm:w-7" />
          </div>
          <p class="text-center text-sm text-muted-foreground sm:text-base">
            {{ t('mugConfigurator.steps.generate.magicCoinsUnavailable') }}
          </p>
          <Button variant="outline" size="sm" @click="magicCoinsStore.fetchBalance()">
            <RefreshCw class="h-3.5 w-3.5" />
            {{ t('mugConfigurator.steps.generate.retryButton') }}
          </Button>
        </div>
      </div>
    </div>

    <!-- Error state -->
    <div v-else-if="imageGeneration.error" class="mt-6 sm:mt-8">
      <div
        class="overflow-hidden rounded-xl border border-border bg-card shadow-[0_1px_3px_oklch(0_0_0_/_0.06),0_4px_12px_oklch(0_0_0_/_0.04)] motion-safe:animate-wizard-step-enter motion-reduce:animate-none"
      >
        <div
          class="flex flex-col items-center justify-center gap-5 bg-[linear-gradient(180deg,oklch(0.98_0.01_20_/_0.3)_0%,oklch(0.99_0.005_20_/_0.2)_100%)] p-10 sm:p-14"
        >
          <div
            class="flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10 sm:h-[4.5rem] sm:w-[4.5rem]"
          >
            <AlertCircle class="h-6 w-6 text-destructive sm:h-7 sm:w-7" />
          </div>
          <p class="text-center text-sm text-muted-foreground sm:text-base">
            {{ generationErrorMessage }}
          </p>
          <Button variant="outline" size="sm" :disabled="!canGenerate" @click="generate">
            <RefreshCw class="h-3.5 w-3.5" />
            {{ t('mugConfigurator.steps.generate.retryButton') }}
          </Button>
        </div>
      </div>
    </div>

    <!-- Success state -->
    <div v-else-if="imageGeneration.hasImages" class="generate-result mt-6 sm:mt-8">
      <div class="generate-result-toolbar mb-4 flex justify-center md:justify-end">
        <Button v-if="canGenerate" variant="outline" size="sm" @click="generate">
          <RefreshCw class="h-3.5 w-3.5" />
          {{ t('mugConfigurator.steps.generate.generateAnother') }} ·
          {{
            t('mugConfigurator.steps.generate.costSuffix', {
              coins: IMAGE_GENERATION_MAGIC_COIN_COST,
            })
          }}
        </Button>
        <Button v-else as-child variant="outline" size="sm">
          <RouterLink :to="MAGIC_COINS_ROUTE">
            {{ t('mugConfigurator.steps.generate.refillMagicCoins') }}
          </RouterLink>
        </Button>
      </div>

      <div
        class="generate-result-layout grid gap-4"
        :class="{
          'generate-result-layout--with-variants md:grid-cols-[7rem_minmax(0,1fr)] md:items-start':
            imageGeneration.imageCount > 1,
        }"
      >
        <div
          class="generate-preview-shell flex min-w-0 justify-center"
          :class="{ 'md:col-start-2 md:row-start-1': imageGeneration.imageCount > 1 }"
        >
          <Button
            type="button"
            variant="ghost"
            class="generate-preview relative flex h-auto max-w-full appearance-none items-center justify-center rounded-lg border-0 bg-transparent p-0 font-[inherit] text-inherit shadow-none hover:bg-transparent hover:text-inherit hover:shadow-none motion-safe:hover:scale-100"
            :aria-label="t('mugConfigurator.steps.generate.resultTitle')"
            @click="lightboxOpen = true"
          >
            <img
              :src="imageGeneration.selectedImageUrl!"
              :alt="t('mugConfigurator.steps.generate.resultTitle')"
              class="generate-preview-image max-h-[55vh] max-w-full rounded-lg object-contain sm:max-h-[65vh]"
            />
            <span
              class="absolute right-3 top-3 flex h-8 w-8 items-center justify-center rounded-md bg-black/40 text-white/90 backdrop-blur-sm transition-colors hover:bg-black/60 sm:right-4 sm:top-4"
              aria-hidden="true"
            >
              <ZoomIn class="h-4 w-4" />
            </span>
          </Button>
        </div>

        <!-- Thumbnail gallery -->
        <aside
          v-if="imageGeneration.imageCount > 1"
          class="generate-variants-rail min-w-0 md:col-start-1 md:row-start-1"
          aria-labelledby="generate-variants-label"
        >
          <p
            id="generate-variants-label"
            class="mb-2 text-xs font-medium text-muted-foreground sm:text-sm"
          >
            {{ t('mugConfigurator.steps.generate.historyLabel') }}
          </p>
          <VariantGallery
            class="generate-variants-gallery md:max-h-[65vh] md:flex-col md:flex-nowrap md:overflow-x-hidden md:overflow-y-auto md:pb-0 md:pr-1"
            :images="imageGeneration.generatedImages"
            :selected-image-id="imageGeneration.selectedImageId"
            @select="imageGeneration.selectImage"
          />
        </aside>
      </div>
    </div>

    <!-- Idle state (no generation started yet, no file) -->
    <div v-else class="mt-6 sm:mt-8">
      <div
        class="overflow-hidden rounded-xl border border-border bg-card shadow-[0_1px_3px_oklch(0_0_0_/_0.06),0_4px_12px_oklch(0_0_0_/_0.04)] motion-safe:animate-wizard-step-enter motion-reduce:animate-none"
      >
        <div class="flex flex-col items-center justify-center gap-5 p-10 sm:p-14">
          <div class="generate-icon-outer relative">
            <div
              class="relative flex h-16 w-16 items-center justify-center rounded-full bg-brand-gradient shadow-[0_2px_8px_oklch(0.61_0.19_35_/_0.25),0_8px_24px_oklch(0.61_0.19_35_/_0.15)] sm:h-[4.5rem] sm:w-[4.5rem]"
            >
              <Sparkles class="h-6 w-6 text-white sm:h-7 sm:w-7" />
            </div>
          </div>
          <Button @click="generate" :disabled="!wizard.uploadedFile || !canGenerate">
            <Sparkles class="h-4 w-4" />
            {{ t('mugConfigurator.steps.generate.generateButton') }} ·
            {{
              t('mugConfigurator.steps.generate.costSuffix', {
                coins: IMAGE_GENERATION_MAGIC_COIN_COST,
              })
            }}
          </Button>
        </div>
      </div>
    </div>

    <!-- Lightbox dialog -->
    <Dialog v-model:open="lightboxOpen">
      <DialogContent
        class="lightbox-dialog max-w-[95vw] max-h-[95vh] border-0 bg-transparent p-0 shadow-none sm:max-w-[90vw]"
      >
        <DialogTitle class="sr-only">
          {{ t('mugConfigurator.steps.generate.resultTitle') }}
        </DialogTitle>
        <DialogDescription class="sr-only">
          {{ t('mugConfigurator.steps.generate.resultTitle') }}
        </DialogDescription>
        <img
          :src="imageGeneration.selectedImageUrl!"
          :alt="t('mugConfigurator.steps.generate.resultTitle')"
          class="max-h-[90vh] w-auto rounded-lg object-contain"
        />
      </DialogContent>
    </Dialog>
  </div>
</template>

<style scoped>
/* CSS exceptions: generation-specific aurora, shimmer, particle/orb animations, the progress sweep, and pseudo-element glow stay local because Tailwind utility chains would be harder to maintain and easier to regress visually. */

/* === Aurora gradients === */
.generate-aurora-1 {
  background:
    radial-gradient(ellipse 80% 50% at 25% 35%, oklch(0.3 0.16 280 / 0.7), transparent),
    radial-gradient(ellipse 50% 70% at 75% 65%, oklch(0.25 0.14 310 / 0.5), transparent);
  animation: generate-aurora-1 7s ease-in-out infinite alternate;
}

.generate-aurora-2 {
  background:
    radial-gradient(ellipse 60% 60% at 65% 25%, oklch(0.22 0.12 260 / 0.5), transparent),
    radial-gradient(ellipse 70% 40% at 35% 75%, oklch(0.28 0.15 295 / 0.4), transparent);
  animation: generate-aurora-2 9s ease-in-out infinite alternate;
}

@keyframes generate-aurora-1 {
  from {
    transform: translate(-4%, -2%) scale(1);
    opacity: 0.7;
  }
  to {
    transform: translate(4%, 2%) scale(1.08);
    opacity: 1;
  }
}

@keyframes generate-aurora-2 {
  from {
    transform: translate(3%, 4%) scale(1.05);
    opacity: 0.5;
  }
  to {
    transform: translate(-3%, -4%) scale(1);
    opacity: 0.85;
  }
}

/* === Shimmer sweep === */
.generate-shimmer {
  background: linear-gradient(105deg, transparent 40%, oklch(1 0 0 / 0.03) 50%, transparent 60%);
  background-size: 200% 100%;
  animation: generate-shimmer 3.5s ease-in-out infinite;
}

@keyframes generate-shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

/* === Bloom glow === */
.generate-bloom {
  background: radial-gradient(
    circle,
    oklch(0.4 0.2 285 / 0.35),
    oklch(0.3 0.15 300 / 0.15) 45%,
    transparent 70%
  );
  animation: generate-bloom 4s ease-in-out infinite;
}

@keyframes generate-bloom {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.6;
  }
  50% {
    transform: scale(1.12);
    opacity: 1;
  }
}

/* === Ring outer (slow CW) === */
.generate-ring-outer {
  border: 2px dashed oklch(0.6 0.18 285 / 0.25);
  animation: generate-spin-cw 16s linear infinite;
}

@keyframes generate-spin-cw {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

/* === Ring middle (medium CCW) === */
.generate-ring-middle {
  border: 2px solid oklch(0.55 0.2 280 / 0.12);
  border-top-color: oklch(0.7 0.22 290 / 0.7);
  border-right-color: oklch(0.6 0.2 300 / 0.4);
  animation: generate-spin-ccw 10s linear infinite;
}

@keyframes generate-spin-ccw {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(-360deg);
  }
}

/* === Ring inner (pulsing) === */
.generate-ring-inner {
  border: 2px solid oklch(0.5 0.18 280 / 0.15);
  animation: generate-ring-pulse 3s ease-in-out infinite;
}

@keyframes generate-ring-pulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.3;
    border-color: oklch(0.5 0.18 280 / 0.15);
  }
  50% {
    transform: scale(1.05);
    opacity: 0.7;
    border-color: oklch(0.65 0.22 290 / 0.5);
  }
}

/* === Orbit particles === */
.generate-orbit-far {
  animation: generate-spin-cw 22s linear infinite;
}

.generate-orbit-near {
  animation: generate-spin-ccw 14s linear infinite;
}

.generate-particle-far {
  width: 5px;
  height: 5px;
  margin: -2.5px;
  border-radius: 9999px;
  background: oklch(0.75 0.16 290);
  box-shadow:
    0 0 6px oklch(0.6 0.2 285 / 0.8),
    0 0 16px oklch(0.5 0.18 280 / 0.4);
  animation: generate-twinkle 2.5s ease-in-out infinite;
}

.generate-particle-far:nth-child(2n) {
  animation-delay: 0.8s;
}

.generate-particle-far:nth-child(3n) {
  animation-delay: 1.6s;
}

.generate-particle-near {
  width: 4px;
  height: 4px;
  margin: -2px;
  border-radius: 9999px;
  background: oklch(0.8 0.14 300);
  box-shadow: 0 0 8px oklch(0.65 0.18 295 / 0.7);
  animation: generate-twinkle 2s ease-in-out infinite;
}

.generate-particle-near:nth-child(2n) {
  animation-delay: 0.5s;
}

@keyframes generate-twinkle {
  0%,
  100% {
    opacity: 0.3;
  }
  50% {
    opacity: 1;
  }
}

/* === Center orb === */
.generate-center-orb {
  background: linear-gradient(135deg, oklch(0.5 0.22 275), oklch(0.6 0.24 305));
  box-shadow:
    0 0 20px oklch(0.5 0.2 280 / 0.5),
    0 0 60px oklch(0.45 0.18 285 / 0.3),
    inset 0 1px 1px oklch(1 0 0 / 0.1);
}

/* === Icon pulse === */
.generate-icon-pulse {
  animation: generate-icon-pulse 3s ease-in-out infinite;
}

@keyframes generate-icon-pulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.85;
  }
  50% {
    transform: scale(1.18);
    opacity: 1;
  }
}

/* === Progress bar === */
.generate-progress-bar {
  background: linear-gradient(
    90deg,
    transparent,
    oklch(0.6 0.22 285 / 0.8) 30%,
    oklch(0.7 0.2 300) 50%,
    oklch(0.6 0.22 285 / 0.8) 70%,
    transparent
  );
  background-size: 300% 100%;
  animation: generate-progress-sweep 2.5s ease-in-out infinite;
}

@keyframes generate-progress-sweep {
  0% {
    background-position: 100% 0;
  }
  100% {
    background-position: -100% 0;
  }
}

.generate-icon-outer::before {
  content: '';
  position: absolute;
  inset: -12px;
  border-radius: 9999px;
  background: radial-gradient(circle, oklch(0.61 0.19 35 / 0.1), transparent 70%);
}

/* CSS exception: DialogContent owns the rendered close button, so this lightbox-only child override stays global. */
:global(.lightbox-dialog > button:last-child) {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  border-radius: 0.375rem;
  background: oklch(1 0 0 / 0.2);
  color: white;
  backdrop-filter: blur(8px);
  opacity: 1;
}

:global(.lightbox-dialog > button:last-child:hover) {
  background: oklch(1 0 0 / 0.35);
}
</style>
