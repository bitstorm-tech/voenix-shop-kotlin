<script setup lang="ts">
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import type { Step } from './types'

interface Props {
  currentStep: number
  steps: Step[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'step-click': [step: number]
}>()

function handleStepClick(stepNumber: number) {
  if (stepNumber < props.currentStep) {
    emit('step-click', stepNumber)
  }
}

function isCompleted(stepNumber: number): boolean {
  return stepNumber < props.currentStep
}

function isCurrent(stepNumber: number): boolean {
  return stepNumber === props.currentStep
}

function isFuture(stepNumber: number): boolean {
  return stepNumber > props.currentStep
}
</script>

<template>
  <div>
    <!-- Row 1: Circles and connector lines -->
    <div class="flex items-center">
      <template v-for="(step, index) in steps" :key="`circle-${step.number}`">
        <Button
          type="button"
          variant="icon"
          size="icon-lg"
          :disabled="isFuture(step.number)"
          :aria-current="isCurrent(step.number) ? 'step' : undefined"
          :class="
            cn(
              'shrink-0 size-10 rounded-full border-2 p-0 text-sm font-semibold shadow-none transition-colors motion-safe:hover:scale-100',
              isCurrent(step.number) && 'border-primary bg-primary text-primary-foreground',
              isCompleted(step.number) &&
                'border-primary text-primary cursor-pointer hover:bg-primary/10',
              isFuture(step.number) &&
                'border-muted-foreground/30 text-muted-foreground/50 cursor-not-allowed',
            )
          "
          @click="handleStepClick(step.number)"
        >
          {{ step.number }}
        </Button>
        <div
          v-if="index < steps.length - 1"
          :class="
            cn(
              'flex-1 h-0.5 transition-colors',
              step.number < currentStep ? 'bg-primary' : 'bg-muted-foreground/30',
            )
          "
        />
      </template>
    </div>

    <!-- Row 2: Labels -->
    <div class="flex mt-2">
      <template v-for="(step, index) in steps" :key="`label-${step.number}`">
        <div class="shrink-0 w-10 flex justify-center">
          <span
            :class="
              cn(
                'text-xs text-center whitespace-nowrap',
                isCurrent(step.number) && 'text-primary font-medium',
                !isCurrent(step.number) && 'text-muted-foreground',
              )
            "
          >
            {{ step.label }}
          </span>
        </div>
        <div v-if="index < steps.length - 1" class="flex-1" />
      </template>
    </div>
  </div>
</template>
