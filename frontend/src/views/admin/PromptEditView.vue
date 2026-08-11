<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AdminPromptEditor from '@/components/admin/prompts/AdminPromptEditor.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'

const route = useRoute()
const isCreate = computed(() => route.name === 'admin-prompt-new')
const promptId = computed(() => {
  if (isCreate.value) {
    return null
  }

  const rawId = Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
  const value = Number(rawId)
  return Number.isInteger(value) && value > 0 ? value : null
})
</script>

<template>
  <AdminPromptEditor
    v-if="isCreate || promptId !== null"
    :key="isCreate ? 'new' : (promptId ?? 'invalid')"
    :prompt-id="promptId"
  />
  <Card v-else class="space-y-4 p-5">
    <Alert variant="destructive">
      <p class="font-medium">Prompt not found</p>
      <p class="mt-1">Invalid Prompt ID. Return to All Prompts and choose an existing Prompt.</p>
    </Alert>
    <Button as-child variant="outline">
      <RouterLink :to="{ name: 'admin-prompts', query: route.query }"> All Prompts </RouterLink>
    </Button>
  </Card>
</template>
