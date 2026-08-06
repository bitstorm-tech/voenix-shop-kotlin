<script setup lang="ts">
import { RouterLink, type RouteLocationRaw } from 'vue-router'

interface MugCategoryFilterItem {
  id: number | null
  label: string
  count: number
  active: boolean
  to: RouteLocationRaw
}

defineProps<{
  filters: MugCategoryFilterItem[]
  resultLabel: string
}>()
</script>

<template>
  <section class="grid gap-3 pb-1 pt-4" aria-label="Mug categories">
    <div class="flex items-center justify-between gap-4">
      <p class="text-sm font-semibold text-foreground-muted">{{ resultLabel }}</p>
    </div>

    <nav class="scrollbar-hide flex gap-2 overflow-x-auto pb-2" aria-label="Mug category filters">
      <RouterLink
        v-for="filter in filters"
        :key="filter.id ?? 'all'"
        :to="filter.to"
        class="inline-flex min-h-10 shrink-0 items-center gap-2 rounded-md border px-4 py-2 text-sm font-bold no-underline transition-all duration-200 motion-reduce:transition-none"
        :class="
          filter.active
            ? 'border-primary bg-primary text-primary-foreground shadow-md shadow-primary/20'
            : 'border-border bg-background-soft text-foreground-soft hover:border-primary/30 hover:bg-background-warm hover:text-foreground motion-safe:hover:-translate-y-0.5'
        "
        :aria-current="filter.active ? 'page' : undefined"
      >
        <span>{{ filter.label }}</span>
        <span
          class="min-w-6 rounded-sm px-2 py-0.5 text-center text-xs"
          :class="filter.active ? 'bg-white/20' : 'bg-foreground/10 text-foreground-muted'"
        >
          {{ filter.count }}
        </span>
      </RouterLink>
    </nav>
  </section>
</template>
