<script setup lang="ts">
import AdminPlaceholderSection from '@/components/admin/AdminPlaceholderSection.vue'

const highlights = [
  'Fulfillment queues with status, payment, and shipment checkpoints',
  'Exception handling for failed payments, returns, and custom issues',
  'Hooks for order detail drawers, notes, and timeline events',
  'A future-friendly home for bulk actions and support coordination',
]
</script>

<template>
  <AdminPlaceholderSection title="Orders" :highlights="highlights" />
</template>
