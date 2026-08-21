<script setup lang="ts">
import { computed, shallowRef, watch, onBeforeUnmount, type Component, markRaw } from 'vue'
import { useRoute, useRouter, type LocationQueryValue } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowLeft, ArrowRight, Loader2 } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import {
  StepIndicator,
  WizardNavigation,
  SelectMugStep,
  SelectStyleStep,
  UploadImageStep,
  GenerateStep,
  type Step,
} from '@/components/shop/wizard'
import { useWizardStore } from '@/stores/shop/wizard'
import { usePromptsStore } from '@/stores/shop/prompts'
import { useImageGenerationStore } from '@/stores/shop/imageGeneration'
import { useEditorStore } from '@/stores/shop/editor'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const wizardStore = useWizardStore()
const promptsStore = usePromptsStore()
const imageGenerationStore = useImageGenerationStore()
const editorStore = useEditorStore()
const { toast } = useToast()

function parsePromptIdQuery(value: LocationQueryValue | LocationQueryValue[] | undefined) {
  const rawValue = Array.isArray(value) ? value[0] : value

  if (typeof rawValue !== 'string' || !/^[1-9]\d*$/.test(rawValue)) {
    return null
  }

  const promptId = Number(rawValue)
  return Number.isSafeInteger(promptId) ? promptId : null
}

const hasPromptIdQuery = route.query.promptId !== undefined
const requestedPromptId = parsePromptIdQuery(route.query.promptId)

onBeforeUnmount(() => {
  wizardStore.resetWizard()
  imageGenerationStore.reset()
})

const includeProductStep = shallowRef(!wizardStore.hasSelectedMug)
const currentStep = shallowRef(1)
const isCreatingDraft = shallowRef(false)
const isValidatingPromptQuery = shallowRef(requestedPromptId !== null)
const hasValidPreselectedPrompt = shallowRef(false)

watch(currentStep, () => {
  window.scrollTo({ top: 0 })
})

interface StepDef {
  id: 'selectMug' | 'selectStyle' | 'uploadImage' | 'generate'
  labelKey: string
  component: Component
  canProceed: () => boolean
}

const selectMug: StepDef = {
  id: 'selectMug',
  labelKey: 'mugConfigurator.steps.selectMug.label',
  component: markRaw(SelectMugStep),
  canProceed: () => wizardStore.hasSelectedMug,
}
const selectStyle: StepDef = {
  id: 'selectStyle',
  labelKey: 'mugConfigurator.steps.selectStyle.label',
  component: markRaw(SelectStyleStep),
  canProceed: () => wizardStore.hasSelectedPrompt,
}
const uploadImage: StepDef = {
  id: 'uploadImage',
  labelKey: 'mugConfigurator.steps.uploadImage.label',
  component: markRaw(UploadImageStep),
  canProceed: () => wizardStore.hasUploadedImage,
}
const generate: StepDef = {
  id: 'generate',
  labelKey: 'mugConfigurator.steps.generate.label',
  component: markRaw(GenerateStep),
  canProceed: () => imageGenerationStore.selectedImageId != null,
}

const styleFirstOrder: StepDef[] = [selectStyle, selectMug, uploadImage, generate]
const uploadFirstOrder: StepDef[] = [uploadImage, selectMug, selectStyle, generate]

interface StepConfig {
  number: number
  id: StepDef['id']
  labelKey: string
  component: Component
  canProceed: () => boolean
}

const steps = computed<StepConfig[]>(() => {
  const uploadFirst = route.query.start === 'upload'
  const base = uploadFirst ? uploadFirstOrder : styleFirstOrder
  const excludedSteps = new Set<StepDef['id']>()
  if (!includeProductStep.value) {
    excludedSteps.add('selectMug')
  }
  // Campaign links fix the style up front; the visitor only uploads and generates.
  if (uploadFirst && hasValidPreselectedPrompt.value) {
    excludedSteps.add('selectStyle')
  }
  const orderedSteps = base.filter((step) => !excludedSteps.has(step.id))
  return orderedSteps.map((s, i) => ({ ...s, number: i + 1 }))
})

const totalSteps = computed(() => steps.value.length)

const stepIndicatorSteps = computed<Step[]>(() =>
  steps.value.map(({ number, labelKey }) => ({ number, label: t(labelKey) })),
)

const currentStepComponent = computed(() => {
  const step = steps.value[currentStep.value - 1]
  return step?.component ?? steps.value[0]?.component ?? SelectMugStep
})

const isLastStep = computed(() => currentStep.value === totalSteps.value)

const canProceed = computed(() => {
  if (isCreatingDraft.value) return false
  const step = steps.value[currentStep.value - 1]
  return step?.canProceed() ?? false
})

async function validatePromptQuery() {
  if (requestedPromptId === null) {
    if (hasPromptIdQuery) {
      wizardStore.clearPromptSelection()
    }
    return
  }

  isValidatingPromptQuery.value = true

  try {
    await promptsStore.fetchPrompts()
    const selectedPrompt = promptsStore.getPromptById(requestedPromptId)

    if (!selectedPrompt) {
      wizardStore.clearPromptSelection()
      hasValidPreselectedPrompt.value = false
      currentStep.value = 1
      return
    }

    wizardStore.selectPrompt(selectedPrompt.id)
    hasValidPreselectedPrompt.value = true
    // Style-first flows jump past the already answered style step; upload-first flows drop it.
    currentStep.value = route.query.start !== 'upload' && totalSteps.value > 1 ? 2 : 1
  } finally {
    isValidatingPromptQuery.value = false
  }
}

function goToStep(step: number) {
  if (step >= 1 && step <= currentStep.value) {
    currentStep.value = step
  }
}

async function openEditorDraft() {
  if (isCreatingDraft.value) return
  isCreatingDraft.value = true

  try {
    if (wizardStore.selectedMugId === null || wizardStore.selectedVariantId === null) {
      throw new Error(t('mugConfigurator.nav.openEditorError'))
    }

    const draft = editorStore.createDraftFromGeneratedImages({
      articleId: wizardStore.selectedMugId,
      variantId: wizardStore.selectedVariantId,
      images: imageGenerationStore.generatedImages,
    })

    await router.push({ name: 'editor', params: { draftId: draft.id } })
  } catch (err) {
    toast({
      title: err instanceof Error ? err.message : t('mugConfigurator.nav.openEditorError'),
      variant: 'destructive',
    })
  } finally {
    isCreatingDraft.value = false
  }
}

function nextStep() {
  if (currentStep.value < totalSteps.value) {
    currentStep.value++
  } else {
    openEditorDraft()
  }
}

function prevStep() {
  if (currentStep.value > 1) {
    currentStep.value--
  }
}

void validatePromptQuery()
</script>

<template>
  <div
    v-if="isValidatingPromptQuery"
    class="mx-auto flex min-h-[24rem] w-full max-w-3xl flex-col items-center justify-center gap-3 text-center"
    role="status"
    aria-live="polite"
  >
    <Loader2 class="h-7 w-7 animate-spin text-primary" aria-hidden="true" />
    <p class="text-sm font-medium text-muted-foreground">
      {{ t('mugConfigurator.loadingSelectedStyle') }}
    </p>
  </div>

  <section v-else class="mx-auto w-full space-y-8 pb-8 md:pb-12">
    <div class="sticky top-0 z-30">
      <div
        aria-hidden="true"
        class="pointer-events-none absolute inset-y-0 left-1/2 w-screen -translate-x-1/2 bg-background/80 backdrop-blur-xl supports-[backdrop-filter]:bg-background/60"
      />

      <!-- Step Indicator (desktop only) -->
      <div class="relative space-y-6 py-4">
        <StepIndicator
          class="hidden md:block"
          :current-step="currentStep"
          :steps="stepIndicatorSteps"
          @step-click="goToStep"
        />

        <!-- Navigation -->
        <WizardNavigation
          :current-step="currentStep"
          :total-steps="totalSteps"
          :steps="stepIndicatorSteps"
          :can-proceed="canProceed"
          :is-submitting="isCreatingDraft"
          :is-last-step="isLastStep"
          @back="prevStep"
          @next="nextStep"
        />
      </div>
    </div>

    <!-- Step Content -->
    <component :is="currentStepComponent" />

    <!-- Spacer so floating button doesn't cover content on mobile -->
    <div class="h-20 md:hidden" />

    <!-- Floating "Weiter" button (mobile only) -->
    <div class="fixed inset-x-0 bottom-0 z-40 md:hidden">
      <div
        class="bg-gradient-to-t from-background from-60% to-transparent px-4 pb-[max(1rem,env(safe-area-inset-bottom))] pt-6"
      >
        <div class="flex gap-2">
          <Button
            v-if="currentStep > 1"
            variant="outline"
            size="lg"
            class="shrink-0"
            @click="prevStep"
          >
            <ArrowLeft class="h-4 w-4" />
            {{ t('mugConfigurator.nav.back') }}
          </Button>
          <Button class="flex-1" size="lg" :disabled="!canProceed" @click="nextStep">
            <Loader2 v-if="isCreatingDraft" class="h-4 w-4 animate-spin" />
            <template v-else>
              {{ isLastStep ? t('mugConfigurator.nav.finish') : t('mugConfigurator.nav.next') }}
              <ArrowRight v-if="!isLastStep" class="ml-2 h-4 w-4" />
            </template>
          </Button>
        </div>
      </div>
    </div>
  </section>
</template>
