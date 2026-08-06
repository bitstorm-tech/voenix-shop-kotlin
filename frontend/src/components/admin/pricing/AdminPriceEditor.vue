<script setup lang="ts">
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import PriceSectionCard from './PriceSectionCard.vue'
import type { AdminPriceFieldTexts, AdminPriceFormState } from '@/lib/adminPrice'
import type {
  AdminPriceDto,
  PriceCalculationMode,
  PriceVatDto,
  PurchaseActiveRow,
  SalesActiveRow,
} from '@/stores/admin/prices'

const props = withDefaults(
  defineProps<{
    description: string
    form: Readonly<AdminPriceFormState>
    fields: Readonly<AdminPriceFieldTexts>
    price: AdminPriceDto | null
    vatOptions: PriceVatDto[]
    isLoading: boolean
    isCalculating: boolean
    setupError: string | null
    error: string | null
    inputError: string | null
    disabled?: boolean
    retryLabel?: string
  }>(),
  {
    disabled: false,
    retryLabel: 'Erneut versuchen',
  },
)

const emit = defineEmits<{
  purchaseVatChange: [vatId: number | null]
  salesVatChange: [vatId: number | null]
  purchaseModeChange: [mode: PriceCalculationMode]
  salesModeChange: [mode: PriceCalculationMode]
  purchaseActiveRowChange: [row: PurchaseActiveRow]
  salesActiveRowChange: [row: SalesActiveRow]
  purchasePriceChange: [value: string]
  purchaseCostChange: [value: string]
  purchaseCostPercentChange: [value: string]
  salesMarginChange: [value: string]
  salesMarginPercentChange: [value: string]
  salesTotalChange: [value: string]
  retrySetup: []
  retryCalculation: []
}>()
</script>

<template>
  <div class="space-y-4">
    <div
      v-if="props.isLoading"
      class="rounded-lg border border-border bg-muted/10 px-4 py-10 text-center text-sm text-muted-foreground"
    >
      Preisvorlage wird geladen...
    </div>

    <div v-else-if="props.setupError" class="space-y-3">
      <Alert variant="destructive">
        {{ props.setupError }}
      </Alert>
      <Button
        type="button"
        variant="outline"
        :disabled="props.disabled"
        @click="emit('retrySetup')"
      >
        {{ props.retryLabel }}
      </Button>
    </div>

    <template v-else>
      <div class="flex min-h-6 items-center justify-between gap-3">
        <p class="text-sm text-muted-foreground">
          {{ props.description }}
        </p>
        <span
          v-if="props.isCalculating"
          class="inline-flex shrink-0 items-center rounded-full bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary"
        >
          Berechnet...
        </span>
      </div>

      <div v-if="props.inputError || props.error" class="space-y-3">
        <Alert variant="destructive">
          {{ props.inputError || props.error }}
        </Alert>
        <Button
          v-if="props.error && !props.inputError"
          type="button"
          variant="outline"
          :disabled="props.disabled || props.isCalculating"
          @click="emit('retryCalculation')"
        >
          {{ props.retryLabel }}
        </Button>
      </div>

      <fieldset :disabled="props.disabled" class="contents">
        <PriceSectionCard
          kind="purchase"
          title="Einkauf"
          subtitle="Einkaufspreise und Berechnungen"
          :form="props.form"
          :fields="props.fields"
          :price="props.price"
          :vat-options="props.vatOptions"
          @purchase-vat-change="emit('purchaseVatChange', $event)"
          @sales-vat-change="emit('salesVatChange', $event)"
          @purchase-mode-change="emit('purchaseModeChange', $event)"
          @sales-mode-change="emit('salesModeChange', $event)"
          @purchase-active-row-change="emit('purchaseActiveRowChange', $event)"
          @sales-active-row-change="emit('salesActiveRowChange', $event)"
          @purchase-price-change="emit('purchasePriceChange', $event)"
          @purchase-cost-change="emit('purchaseCostChange', $event)"
          @purchase-cost-percent-change="emit('purchaseCostPercentChange', $event)"
          @sales-margin-change="emit('salesMarginChange', $event)"
          @sales-margin-percent-change="emit('salesMarginPercentChange', $event)"
          @sales-total-change="emit('salesTotalChange', $event)"
        />

        <PriceSectionCard
          kind="sales"
          title="Verkauf"
          subtitle="Verkaufspreise und Margenberechnungen"
          :form="props.form"
          :fields="props.fields"
          :price="props.price"
          :vat-options="props.vatOptions"
          @purchase-vat-change="emit('purchaseVatChange', $event)"
          @sales-vat-change="emit('salesVatChange', $event)"
          @purchase-mode-change="emit('purchaseModeChange', $event)"
          @sales-mode-change="emit('salesModeChange', $event)"
          @purchase-active-row-change="emit('purchaseActiveRowChange', $event)"
          @sales-active-row-change="emit('salesActiveRowChange', $event)"
          @purchase-price-change="emit('purchasePriceChange', $event)"
          @purchase-cost-change="emit('purchaseCostChange', $event)"
          @purchase-cost-percent-change="emit('purchaseCostPercentChange', $event)"
          @sales-margin-change="emit('salesMarginChange', $event)"
          @sales-margin-percent-change="emit('salesMarginPercentChange', $event)"
          @sales-total-change="emit('salesTotalChange', $event)"
        />
      </fieldset>
    </template>
  </div>
</template>
