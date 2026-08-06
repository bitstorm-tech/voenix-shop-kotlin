<script setup lang="ts">
defineProps<{
  title: string
  subtitle: string
}>()
</script>

<template>
  <header class="mb-6 space-y-2 text-center sm:mb-8">
    <h1 class="text-2xl font-bold leading-tight text-foreground sm:text-3xl">
      {{ title }}
    </h1>
    <p class="text-sm leading-6 text-muted-foreground sm:text-base">
      {{ subtitle }}
    </p>
  </header>
</template>
