<script setup lang="ts">
import { ToastProvider, ToastViewport } from 'reka-ui'
import { useToast } from '@/composables/useToast'
import Toast from './Toast.vue'
import ToastTitle from './ToastTitle.vue'
import ToastDescription from './ToastDescription.vue'
import ToastClose from './ToastClose.vue'

const { toasts, dismiss } = useToast()
</script>

<template>
  <ToastProvider :duration="5000" swipe-direction="right">
    <Toast
      v-for="t in toasts"
      :key="t.id"
      :variant="t.variant"
      :duration="t.duration"
      :open="t.open"
      @update:open="
        (open) => {
          if (!open) dismiss(t.id)
        }
      "
    >
      <div class="grid gap-1">
        <ToastTitle v-if="t.title">{{ t.title }}</ToastTitle>
        <ToastDescription v-if="t.description">{{ t.description }}</ToastDescription>
      </div>
      <ToastClose />
    </Toast>

    <ToastViewport
      class="fixed bottom-4 left-1/2 z-[100] flex max-h-screen w-[calc(100%-2rem)] -translate-x-1/2 flex-col-reverse gap-2 sm:flex-col sm:max-w-[420px]"
    />
  </ToastProvider>
</template>
