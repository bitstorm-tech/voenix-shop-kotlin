<script setup lang="ts">
import { Button } from '@/components/ui/button'
import { computed, type Component } from 'vue'

const props = withDefaults(
  defineProps<{
    icon: Component
    title: string
    message: string
    variant?: 'success' | 'error'
    actionTo?: string
    actionLabel?: string
    actionVariant?: 'default' | 'outline'
  }>(),
  {
    variant: 'success',
    actionTo: undefined,
    actionLabel: undefined,
    actionVariant: 'default',
  },
)

const iconWrapperClass = computed(() =>
  props.variant === 'success'
    ? 'bg-success-surface text-success'
    : 'bg-destructive/10 text-destructive',
)
</script>

<template>
  <div class="flex flex-col items-center text-center">
    <div
      class="mb-5 flex size-12 items-center justify-center rounded-full"
      :class="iconWrapperClass"
    >
      <component :is="icon" class="size-6" aria-hidden="true" />
    </div>
    <h1 class="text-2xl font-bold leading-tight text-foreground">
      {{ title }}
    </h1>
    <p class="mt-3 text-sm leading-6 text-muted-foreground sm:text-base">
      {{ message }}
    </p>
    <Button
      v-if="actionTo && actionLabel"
      as-child
      :variant="actionVariant"
      size="lg"
      class="mt-6 w-full"
    >
      <RouterLink :to="actionTo">
        {{ actionLabel }}
      </RouterLink>
    </Button>
  </div>
</template>
