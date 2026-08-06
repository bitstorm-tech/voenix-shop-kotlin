<script setup lang="ts">
import { RouterView } from 'vue-router'
import PwaUpdateBanner from '@/components/shared/PwaUpdateBanner.vue'
import { Toaster } from '@/components/ui/toast'
</script>

<template>
  <RouterView />
  <Toaster />
  <PwaUpdateBanner />
</template>
