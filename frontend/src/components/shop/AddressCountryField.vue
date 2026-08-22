<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Country } from '@/stores/shop/countries'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

/**
 * The country field of an address, in the two shapes the backend actually has:
 *
 * - `select` for a shipping country, fed by the administrable list of `GET /api/countries`;
 * - `text` for a billing country, which the backend shape-validates only and deliberately does
 *   not restrict to that list (`docs/dev/backend/packages/checkout-package.md`).
 */
const props = withDefaults(
  defineProps<{
    id: string
    mode: 'select' | 'text'
    modelValue: string
    options?: Country[]
    errorMessage?: string | null
  }>(),
  {
    options: () => [],
    errorMessage: null,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const { t } = useI18n()

const isSelectUnavailable = computed(() => props.mode === 'select' && props.options.length === 0)
const errorId = computed(() => `${props.id}-error`)
const describedBy = computed(() => (props.errorMessage ? errorId.value : undefined))

function selectCountry(value: string) {
  emit('update:modelValue', value)
}

/** The wire value is an uppercase two-letter code, so the input can only ever contain one. */
function typeCountry(value: string) {
  emit(
    'update:modelValue',
    value
      .replace(/[^A-Za-z]/g, '')
      .toUpperCase()
      .slice(0, 2),
  )
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <Label :for="id">{{ t('checkout.address.country') }}</Label>

    <Select
      v-if="mode === 'select'"
      :model-value="modelValue"
      :disabled="isSelectUnavailable"
      @update:model-value="selectCountry(String($event))"
    >
      <SelectTrigger :id="id" :aria-describedby="describedBy">
        <SelectValue :placeholder="t('checkout.address.countryPlaceholder')" />
      </SelectTrigger>
      <SelectContent>
        <SelectItem
          v-for="country in options"
          :key="country.countryCode"
          :value="country.countryCode"
        >
          {{ country.name }}
        </SelectItem>
      </SelectContent>
    </Select>

    <template v-else>
      <Input
        :id="id"
        :model-value="modelValue"
        maxlength="2"
        autocapitalize="characters"
        autocomplete="country"
        :placeholder="t('checkout.address.countryCodePlaceholder')"
        :aria-describedby="describedBy"
        class="uppercase"
        @update:model-value="typeCountry(String($event))"
      />
      <p class="text-xs text-muted-foreground">{{ t('checkout.address.countryCodeHint') }}</p>
    </template>

    <p v-if="errorMessage" :id="errorId" role="alert" class="text-sm text-destructive">
      {{ errorMessage }}
    </p>
  </div>
</template>
