<script setup lang="ts">
import type { ToastCloseProps } from 'reka-ui'
import { ToastClose } from 'reka-ui'
import { X } from 'lucide-vue-next'

defineProps<ToastCloseProps>()
</script>

<template>
  <ToastClose
    class="absolute right-2 top-2 rounded-md p-1 opacity-0 transition-opacity hover:opacity-100 focus:opacity-100 focus:outline-none focus:ring-2 group-hover:opacity-100"
  >
    <X class="h-4 w-4" />
  </ToastClose>
</template>
