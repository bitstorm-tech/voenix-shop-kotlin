<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  title: string
  // List views switch the header row at `lg`, edit views already at `sm`.
  breakpoint?: 'sm' | 'lg'
}

const props = withDefaults(defineProps<Props>(), {
  breakpoint: 'sm',
})

const layoutClass = computed(() =>
  props.breakpoint === 'lg'
    ? 'flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between'
    : 'flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between',
)
</script>

<template>
  <div :class="layoutClass">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight text-foreground">{{ title }}</h1>
    </div>

    <slot name="actions" />
  </div>
</template>
