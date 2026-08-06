<script setup lang="ts">
withDefaults(
  defineProps<{
    count?: number
  }>(),
  {
    count: 8,
  },
)
</script>

<template>
  <div
    class="grid grid-cols-1 gap-3 pt-4 sm:grid-cols-2 sm:gap-5 md:grid-cols-3 lg:grid-cols-4"
    aria-hidden="true"
  >
    <div
      v-for="n in count"
      :key="n"
      class="flex animate-pulse flex-row overflow-hidden rounded-xl border bg-background-soft motion-reduce:animate-none sm:flex-col"
    >
      <div class="w-[120px] shrink-0 bg-muted sm:aspect-[4/3] sm:w-auto" />
      <div class="flex flex-1 flex-col justify-center gap-3 p-3">
        <div class="h-4 w-16 rounded-full bg-muted" />
        <div class="h-3 w-1/2 rounded-full bg-muted" />
        <div class="h-3 w-4/5 rounded-full bg-muted" />
        <div class="flex gap-2">
          <div v-for="d in 5" :key="d" class="size-4 rounded-full bg-muted" />
        </div>
        <div class="h-9 rounded-full bg-muted" />
      </div>
    </div>
  </div>
</template>
