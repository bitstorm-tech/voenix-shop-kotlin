<script setup lang="ts">
import AdminPlaceholderSection from '@/components/admin/AdminPlaceholderSection.vue'

const highlights = [
  'Define shipping methods and fulfillment handoff rules for future order processing',
  'Prepare delivery regions, carrier notes, and operational cut-off settings',
  'Create a future home for shipping cost and tracking configuration',
]
</script>

<template>
  <AdminPlaceholderSection title="Logistics" :highlights="highlights" />
</template>
