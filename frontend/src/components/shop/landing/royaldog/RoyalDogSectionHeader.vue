<script setup lang="ts">
defineProps<{
  kicker: string
  title: string
}>()
</script>

<template>
  <header class="mx-auto mb-8 max-w-[480px] text-center">
    <p class="mb-2 text-xs font-bold uppercase tracking-[0.18em] text-[color:var(--royal-gold)]">
      {{ kicker }}
    </p>
    <h2 class="font-display text-2xl font-extrabold tracking-normal text-foreground md:text-3xl">
      {{ title }}
    </h2>
  </header>
</template>
