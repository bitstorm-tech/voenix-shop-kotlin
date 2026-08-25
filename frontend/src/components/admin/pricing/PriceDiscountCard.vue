<script setup lang="ts">
import { computed } from 'vue'
import { Label } from '@/components/ui/label'
import { SegmentedControl, SegmentedControlItem } from '@/components/ui/segmented-control'
import PriceAmountField from './PriceAmountField.vue'
import { formatCents, formatPercent, type AdminPriceFieldTexts } from '@/lib/adminPrice'
import type { AdminPriceDto, PriceCalculationMode, PriceDiscountType } from '@/stores/admin/prices'

type AmountColumn = 'net' | 'tax' | 'gross'

/** The segmented control needs a value for "no discount"; the form state keeps `null`. */
const NO_DISCOUNT = 'NONE'

const props = defineProps<{
  form: {
    salesCalculationMode: PriceCalculationMode
    discountType: PriceDiscountType | null
  }
  fields: Readonly<AdminPriceFieldTexts>
  price: AdminPriceDto | null
}>()

const emit = defineEmits<{
  discountTypeChange: [type: PriceDiscountType | null]
  discountValueChange: [value: string]
}>()

const discountKind = computed({
  get: () => props.form.discountType ?? NO_DISCOUNT,
  set: (value: string | undefined) => {
    if (value === NO_DISCOUNT) {
      emit('discountTypeChange', null)
      return
    }

    if (value === 'PERCENTAGE' || value === 'FIXED_AMOUNT') {
      emit('discountTypeChange', value)
    }
  },
})

const isNegativeMargin = computed(() => (props.price?.salesMargin.gross ?? 0) < 0)

function amountValue(
  amount: { net: number; tax: number; gross: number } | undefined,
  column: AmountColumn,
) {
  return amount ? formatCents(amount[column]) : '0,00'
}

function isModeColumn(column: AmountColumn) {
  return (
    (column === 'net' && props.form.salesCalculationMode === 'NET') ||
    (column === 'gross' && props.form.salesCalculationMode === 'GROSS')
  )
}
</script>

<template>
  <section class="overflow-hidden rounded-lg border border-border bg-background">
    <div class="border-b border-border bg-muted/20 px-4 py-3">
      <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <h3 class="text-base font-semibold text-foreground">Discount</h3>
          <p class="text-sm text-muted-foreground">
            An optional reduction of the regular sales price.
          </p>
        </div>
        <div class="grid gap-3 sm:grid-cols-[auto_minmax(8rem,1fr)] lg:min-w-[26rem]">
          <div class="space-y-1.5">
            <Label class="text-xs font-medium">Kind</Label>
            <SegmentedControl v-model="discountKind" type="single" variant="editor" class="w-full">
              <SegmentedControlItem
                :value="NO_DISCOUNT"
                variant="editor"
                class="min-h-7 flex-1 px-3 py-1 text-xs"
              >
                No discount
              </SegmentedControlItem>
              <SegmentedControlItem
                value="PERCENTAGE"
                variant="editor"
                class="min-h-7 flex-1 px-3 py-1 text-xs"
              >
                Percent
              </SegmentedControlItem>
              <SegmentedControlItem
                value="FIXED_AMOUNT"
                variant="editor"
                class="min-h-7 flex-1 px-3 py-1 text-xs"
              >
                Amount
              </SegmentedControlItem>
            </SegmentedControl>
          </div>
          <div v-if="form.discountType !== null" class="space-y-1.5">
            <Label class="text-xs font-medium">Value</Label>
            <PriceAmountField
              :model-value="fields.discountValue"
              :suffix="form.discountType === 'PERCENTAGE' ? '%' : 'EUR'"
              aria-label="Discount value"
              editable
              emphasis
              test-id="price-discount-value"
              @update:model-value="emit('discountValueChange', $event)"
            />
          </div>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 divide-y divide-border">
      <div
        class="hidden grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] gap-2 bg-muted/10 px-4 py-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground md:grid"
      >
        <div>Position</div>
        <div class="text-right">Netto</div>
        <div class="text-right">Steuer</div>
        <div class="text-right">Brutto</div>
      </div>

      <div
        class="grid gap-2 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
      >
        <div class="font-medium text-foreground">Regular sales total</div>
        <PriceAmountField
          v-for="column in ['net', 'tax', 'gross'] as AmountColumn[]"
          :key="column"
          :value="amountValue(price?.regularSalesTotal, column)"
          suffix="EUR"
          :aria-label="`Regular sales total ${column}`"
          disabled
          :test-id="`price-discount-regular-total-${column}`"
        />
      </div>

      <div
        class="grid gap-2 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
      >
        <div class="font-medium text-foreground">Saving</div>
        <PriceAmountField
          v-for="column in ['net', 'tax', 'gross'] as AmountColumn[]"
          :key="column"
          :value="amountValue(price?.salesDiscount, column)"
          suffix="EUR"
          :aria-label="`Saving ${column}`"
          disabled
          :test-id="`price-discount-saving-${column}`"
        />
      </div>

      <div
        class="grid gap-2 bg-primary/5 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
      >
        <div class="font-semibold text-foreground">Effective sales total</div>
        <PriceAmountField
          v-for="column in ['net', 'tax', 'gross'] as AmountColumn[]"
          :key="column"
          :value="amountValue(price?.salesTotal, column)"
          suffix="EUR"
          :aria-label="`Effective sales total ${column}`"
          disabled
          :emphasis="isModeColumn(column)"
          :test-id="`price-discount-effective-total-${column}`"
        />
      </div>

      <div
        class="grid gap-2 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
      >
        <div class="font-medium text-foreground">Effective margin</div>
        <PriceAmountField
          v-for="column in ['net', 'tax', 'gross'] as AmountColumn[]"
          :key="column"
          :value="amountValue(price?.salesMargin, column)"
          suffix="EUR"
          :aria-label="`Effective margin ${column}`"
          disabled
          :test-id="`price-discount-effective-margin-${column}`"
        />
      </div>

      <div
        class="grid gap-2 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
      >
        <div class="font-medium text-foreground">Effective margin %</div>
        <PriceAmountField
          :value="formatPercent(price?.calculatedSalesMarginPercent ?? 0)"
          suffix="%"
          aria-label="Effective margin percent"
          disabled
          test-id="price-discount-effective-margin-percent"
        />
      </div>

      <p
        v-if="isNegativeMargin"
        class="bg-destructive/10 px-4 py-3 text-sm font-medium text-destructive"
        data-testid="price-discount-negative-margin-warning"
      >
        The effective margin is negative: the discounted price is below the purchase total.
      </p>

      <p class="px-4 py-3 text-xs text-muted-foreground">
        The shop strikes through the regular price. Under German price-indication law (PAngV § 11)
        that must be a price actually charged during the last 30 days — only discount when the
        regular price was unchanged for at least 30 days and no other discount ran.
      </p>
    </div>
  </section>
</template>
