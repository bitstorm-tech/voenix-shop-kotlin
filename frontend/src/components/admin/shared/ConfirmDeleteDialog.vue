<script setup lang="ts">
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'

interface Props {
  title: string
  description: string
  confirmLabel?: string
  deleting?: boolean
  confirmTestId?: string
}

withDefaults(defineProps<Props>(), {
  confirmLabel: 'Delete',
  deleting: false,
  confirmTestId: undefined,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'confirm'): void
}>()
</script>

<template>
  <AlertDialog v-model:open="open">
    <AlertDialogContent>
      <AlertDialogHeader>
        <AlertDialogTitle>
          {{ title }}
        </AlertDialogTitle>
        <AlertDialogDescription>
          {{ description }}
        </AlertDialogDescription>
      </AlertDialogHeader>

      <slot />

      <AlertDialogFooter>
        <AlertDialogCancel type="button" :disabled="deleting">Cancel</AlertDialogCancel>
        <Button
          type="button"
          variant="destructive"
          :disabled="deleting"
          :data-testid="confirmTestId"
          @click="emit('confirm')"
        >
          {{ deleting ? 'Deleting...' : confirmLabel }}
        </Button>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
</template>
