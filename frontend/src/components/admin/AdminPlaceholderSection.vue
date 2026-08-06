<script setup lang="ts">
import { ArrowLeft } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'

interface Props {
  title: string
  highlights: string[]
  showBackLink?: boolean
}

withDefaults(defineProps<Props>(), {
  showBackLink: true,
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader :title="title">
      <template #actions>
        <Button v-if="showBackLink" as-child variant="outline" size="sm" class="self-start">
          <RouterLink to="/admin">
            <ArrowLeft class="size-4" />
            Back to overview
          </RouterLink>
        </Button>
      </template>
    </AdminPageHeader>

    <div class="grid gap-4 xl:grid-cols-[minmax(0,1.2fr)_360px]">
      <Card as="section" class="overflow-hidden">
        <div class="border-b border-border px-4 py-3">
          <h2 class="text-sm font-semibold text-foreground">Planned first blocks</h2>
        </div>

        <div class="divide-y divide-border">
          <div
            v-for="highlight in highlights"
            :key="highlight"
            class="px-4 py-3 text-sm text-foreground"
          >
            {{ highlight }}
          </div>
        </div>
      </Card>

      <Card as="aside" class="p-4">
        <h2 class="text-sm font-semibold text-foreground">Next implementation</h2>
        <ul class="mt-3 space-y-2 text-sm text-muted-foreground">
          <li class="rounded-lg border border-border bg-muted/40 px-3 py-2">
            Add data fetch and loading states
          </li>
          <li class="rounded-lg border border-border bg-muted/40 px-3 py-2">
            Introduce filters and table actions
          </li>
          <li class="rounded-lg border border-border bg-muted/40 px-3 py-2">
            Connect mutations to backend validation
          </li>
        </ul>
      </Card>
    </div>
  </section>
</template>
