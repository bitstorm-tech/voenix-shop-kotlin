<script setup lang="ts">
import { Card } from '@/components/ui/card'
</script>

<template>
  <div class="mx-auto flex w-full justify-center py-8 sm:py-12 lg:py-16">
    <Card class="w-full max-w-[28rem] bg-card p-6 shadow-lg sm:p-8 lg:p-10">
      <slot />
    </Card>
  </div>
</template>
