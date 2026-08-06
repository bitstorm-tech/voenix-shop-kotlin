<script setup lang="ts">
import { Label } from '@/components/ui/label'

interface Props {
  label: string
  for: string
  error?: string | null
  hint?: string
}

const props = defineProps<Props>()
</script>

<template>
  <div class="space-y-2">
    <Label :for="props.for">{{ label }}</Label>
    <slot />
    <p v-if="error" class="text-sm text-destructive">{{ error }}</p>
    <p v-else-if="hint" class="text-sm text-muted-foreground">{{ hint }}</p>
  </div>
</template>
