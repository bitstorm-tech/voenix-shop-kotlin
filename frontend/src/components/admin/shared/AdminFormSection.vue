<script setup lang="ts">
import { useId } from 'vue'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { cn } from '@/lib/utils'

/**
 * One section of a long admin form: a card with a heading, an optional line of explanation, and the
 * section's inputs below. A form that shows everything on one page stacks these instead of hiding
 * its parts behind tabs, so each part stays recognisable while the whole form stays in view.
 *
 * `readOnly` marks a section that only shows data another system owns — for example the values a
 * Spreadconnect sync writes. It gets a muted background and a badge, so nobody looks for a way to
 * edit it.
 */
const props = defineProps<{
  title: string
  description?: string
  readOnly?: boolean
}>()

const headingId = useId()
</script>

<template>
  <Card
    as="section"
    :aria-labelledby="headingId"
    :class="cn('space-y-5 p-5', props.readOnly && 'bg-muted/30')"
  >
    <header class="space-y-1 border-b border-border pb-4">
      <div class="flex flex-wrap items-center gap-2">
        <h2 :id="headingId" class="text-lg font-semibold text-foreground">{{ title }}</h2>
        <Badge v-if="readOnly">Read-only</Badge>
      </div>
      <p v-if="description" class="text-sm text-muted-foreground">{{ description }}</p>
    </header>

    <slot />
  </Card>
</template>
