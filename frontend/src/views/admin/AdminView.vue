<script setup lang="ts">
import AdminPlaceholderSection from '@/components/admin/AdminPlaceholderSection.vue'

const highlights = [
  'Overview metrics and operational priorities will be defined in a later slice.',
  'This destination stays available as the first Admin navigation entry.',
  'Future content can combine alerts, queues, and workspace shortcuts once the model is settled.',
]
</script>

<template>
  <AdminPlaceholderSection title="Overview" :highlights="highlights" :show-back-link="false" />
</template>
