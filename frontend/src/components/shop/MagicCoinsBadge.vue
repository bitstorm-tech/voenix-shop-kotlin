<script setup lang="ts">
import { Coins } from 'lucide-vue-next'

defineProps<{
  coins: number | null
}>()
</script>

<template>
  <div class="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5">
    <Coins class="size-4 text-muted-foreground" />
    <span class="text-sm font-medium">{{ coins ?? '–' }}</span>
  </div>
</template>
