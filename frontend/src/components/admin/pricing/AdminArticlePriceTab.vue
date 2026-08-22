<script setup lang="ts">
import AdminPriceEditor from '@/components/admin/pricing/AdminPriceEditor.vue'
import { Alert } from '@/components/ui/alert'
import type { useAdminPriceForm } from '@/composables/useAdminPriceForm'
import type { PriceVatDto } from '@/stores/admin/prices'

/**
 * The price tab of an article editor: the price form of `useAdminPriceForm` wired to the price
 * editor, plus the message a rejected write left on the article's `price` field. Both article
 * editors calculate their price the same way, so both show this tab.
 */
defineProps<{
  articlePrice: ReturnType<typeof useAdminPriceForm>
  vatOptions: PriceVatDto[]
  error?: string
}>()
</script>

<template>
  <div class="space-y-4">
    <Alert v-if="error" variant="destructive">
      {{ error }}
    </Alert>

    <AdminPriceEditor
      description="Article prices are calculated by the backend; changed inputs stay visible."
      :form="articlePrice.form"
      :fields="articlePrice.fields"
      :price="articlePrice.lastCalculatedPrice.value"
      :vat-options="vatOptions"
      :is-loading="articlePrice.isLoading.value"
      :is-calculating="articlePrice.isCalculating.value"
      :setup-error="articlePrice.setupError.value"
      :error="articlePrice.error.value"
      :input-error="articlePrice.inputError.value"
      @retry-setup="articlePrice.initialize(null)"
      @retry-calculation="articlePrice.calculateNow"
      @purchase-vat-change="articlePrice.setPurchaseVatId"
      @sales-vat-change="articlePrice.setSalesVatId"
      @purchase-mode-change="articlePrice.setPurchaseCalculationMode"
      @sales-mode-change="articlePrice.setSalesCalculationMode"
      @purchase-active-row-change="articlePrice.setPurchaseActiveRow"
      @sales-active-row-change="articlePrice.setSalesActiveRow"
      @purchase-price-change="articlePrice.setPurchasePrice"
      @purchase-cost-change="articlePrice.setPurchaseCost"
      @purchase-cost-percent-change="articlePrice.setPurchaseCostPercent"
      @sales-margin-change="articlePrice.setSalesMargin"
      @sales-margin-percent-change="articlePrice.setSalesMarginPercent"
      @sales-total-change="articlePrice.setSalesTotal"
    />
  </div>
</template>
