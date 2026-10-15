<script setup lang="ts">
/**
 * The line under the title of a full-page editor: what the record is right now, before anybody
 * scrolls. The default slot holds the controls and badges that describe its state (the *Active*
 * switch, a *Missing* badge); `facts` are the short label–value pairs next to them, for example
 * the category or the price. A fact without a value is shown as a dash, so a reader learns that
 * nothing is set instead of wondering whether the fact exists.
 */
export interface EditorSummaryFact {
  label: string
  value: string | null
}

defineProps<{
  facts: EditorSummaryFact[]
}>()
</script>

<template>
  <div class="flex flex-wrap items-center gap-x-5 gap-y-2 text-sm" data-testid="editor-summary">
    <slot />
    <dl class="contents">
      <template v-for="fact in facts" :key="fact.label">
        <div class="flex items-baseline gap-1.5">
          <dt class="text-muted-foreground">{{ fact.label }}</dt>
          <dd class="font-medium text-foreground">{{ fact.value ?? '—' }}</dd>
        </div>
      </template>
    </dl>
  </div>
</template>
