<script setup lang="ts">
import { computed } from 'vue'
import { Search } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import FormField from '@/components/admin/shared/FormField.vue'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'

interface Props {
  /** The raw text the admin typed; the view owns it so a failed lookup keeps the input filled. */
  isLoading: boolean
  error?: string | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  submit: [orderId: number]
}>()

const orderId = defineModel<string>({ required: true })
const { t } = useI18n()

/** Order ids are positive whole numbers; anything else never named an order. */
const parsedOrderId = computed(() => {
  const trimmed = orderId.value.trim()
  if (!/^\d+$/.test(trimmed)) {
    return null
  }

  const parsed = Number(trimmed)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
})

const canSubmit = computed(() => parsedOrderId.value !== null && !props.isLoading)

function submit() {
  if (parsedOrderId.value === null) {
    return
  }

  emit('submit', parsedOrderId.value)
}
</script>

<template>
  <Card class="p-4">
    <div class="flex flex-col gap-3 sm:flex-row sm:items-end">
      <FormField
        class="flex-1"
        :label="t('admin.orders.lookup.label')"
        for="production-pdf-order-id"
        :error="props.error ?? null"
        :hint="t('admin.orders.lookup.hint')"
      >
        <Input
          id="production-pdf-order-id"
          v-model="orderId"
          inputmode="numeric"
          autocomplete="off"
          :placeholder="t('admin.orders.lookup.placeholder')"
          @keyup.enter="submit"
        />
      </FormField>

      <Button class="sm:w-auto" :disabled="!canSubmit" @click="submit">
        <Search class="size-4" />
        {{ t('admin.orders.lookup.submit') }}
      </Button>
    </div>
  </Card>
</template>
