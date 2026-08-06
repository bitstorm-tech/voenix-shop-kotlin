<script setup lang="ts">
import AdminPlaceholderSection from '@/components/admin/AdminPlaceholderSection.vue'

const highlights = [
  'Collect operational exceptions from orders, payments, fulfillment, and support handovers',
  'Prepare triage states, ownership, severity, and resolution notes for admin follow-up',
  'Create a future workspace for issue history, escalations, and customer-facing outcomes',
]
</script>

<template>
  <AdminPlaceholderSection title="Issues" :highlights="highlights" />
</template>
