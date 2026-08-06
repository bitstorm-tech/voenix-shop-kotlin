<script setup lang="ts">
import { computed } from 'vue'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { SegmentedControl, SegmentedControlItem } from '@/components/ui/segmented-control'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import PriceAmountField from './PriceAmountField.vue'
import { formatCents, formatPercent, type AdminPriceFieldTexts } from '@/lib/adminPrice'
import type {
  AdminPriceDto,
  PriceCalculationMode,
  PriceVatDto,
  PurchaseActiveRow,
  SalesActiveRow,
} from '@/stores/admin/prices'

type SectionKind = 'purchase' | 'sales'
type AmountColumn = 'net' | 'tax' | 'gross'

const activeRowRadioClass =
  'size-4 min-w-4 rounded-full p-0 shadow-none data-[state=checked]:bg-background data-[state=checked]:text-primary'

const props = defineProps<{
  kind: SectionKind
  title: string
  subtitle: string
  form: {
    purchaseVatId: number | null
    purchaseCalculationMode: PriceCalculationMode
    purchaseActiveRow: PurchaseActiveRow
    salesVatId: number | null
    salesCalculationMode: PriceCalculationMode
    salesActiveRow: SalesActiveRow
  }
  fields: Readonly<AdminPriceFieldTexts>
  price: AdminPriceDto | null
  vatOptions: PriceVatDto[]
}>()

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
}>()

const vatSelectValue = computed({
  get: () => {
    const id = props.kind === 'purchase' ? props.form.purchaseVatId : props.form.salesVatId
    return id === null ? '' : id.toString()
  },
  set: (value: string) => {
    const vatId = value === '' ? null : Number(value)
    if (props.kind === 'purchase') {
      emit('purchaseVatChange', vatId)
      return
    }

    emit('salesVatChange', vatId)
  },
})

const mode = computed({
  get: () =>
    props.kind === 'purchase'
      ? props.form.purchaseCalculationMode
      : props.form.salesCalculationMode,
  set: (value: string | undefined) => {
    if (value !== 'NET' && value !== 'GROSS') return

    if (props.kind === 'purchase') {
      emit('purchaseModeChange', value)
      return
    }

    emit('salesModeChange', value)
  },
})

function amountValue(
  amount: { net: number; tax: number; gross: number } | undefined,
  column: AmountColumn,
) {
  if (!amount) {
    return '0,00'
  }

  return formatCents(amount[column])
}

function percentValue(value: number | undefined) {
  return value === undefined ? '0' : formatPercent(value)
}

function isModeColumn(column: AmountColumn) {
  return (
    (column === 'net' && mode.value === 'NET') || (column === 'gross' && mode.value === 'GROSS')
  )
}

function amountTestId(row: string, column: AmountColumn) {
  return `price-${props.kind}-${row}-${column}`
}
</script>

<template>
  <section class="overflow-hidden rounded-lg border border-border bg-background">
    <div class="border-b border-border bg-muted/20 px-4 py-3">
      <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <h3 class="text-base font-semibold text-foreground">{{ title }}</h3>
          <p class="text-sm text-muted-foreground">{{ subtitle }}</p>
        </div>
        <div class="grid gap-3 sm:grid-cols-[minmax(12rem,1fr)_auto] lg:min-w-[26rem]">
          <div class="space-y-1.5">
            <Label :for="`price-${kind}-vat`" class="text-xs font-medium">Steuersatz</Label>
            <Select v-model="vatSelectValue" :disabled="vatOptions.length === 0">
              <SelectTrigger :id="`price-${kind}-vat`" class="h-8">
                <SelectValue placeholder="Steuersatz" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="vat in vatOptions" :key="vat.id" :value="vat.id.toString()">
                  {{ vat.name }} ({{ vat.percent }}%)
                </SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div class="space-y-1.5">
            <Label class="text-xs font-medium">Berechnung</Label>
            <SegmentedControl v-model="mode" type="single" variant="editor" class="w-full">
              <SegmentedControlItem
                value="NET"
                variant="editor"
                class="min-h-7 flex-1 px-3 py-1 text-xs"
              >
                Netto
              </SegmentedControlItem>
              <SegmentedControlItem
                value="GROSS"
                variant="editor"
                class="min-h-7 flex-1 px-3 py-1 text-xs"
              >
                Brutto
              </SegmentedControlItem>
            </SegmentedControl>
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

      <template v-if="kind === 'purchase'">
        <div
          class="grid gap-2 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
        >
          <div class="font-medium text-foreground">Einkaufspreis</div>
          <PriceAmountField
            :model-value="fields.purchasePrice"
            :value="amountValue(price?.purchasePrice, 'net')"
            suffix="EUR"
            aria-label="Einkaufspreis netto"
            :editable="isModeColumn('net')"
            :test-id="amountTestId('price', 'net')"
            @update:model-value="emit('purchasePriceChange', $event)"
          />
          <PriceAmountField
            :value="amountValue(price?.purchasePrice, 'tax')"
            suffix="EUR"
            aria-label="Einkaufspreis Steuer"
            disabled
            :test-id="amountTestId('price', 'tax')"
          />
          <PriceAmountField
            :model-value="fields.purchasePrice"
            :value="amountValue(price?.purchasePrice, 'gross')"
            suffix="EUR"
            aria-label="Einkaufspreis brutto"
            :editable="isModeColumn('gross')"
            :test-id="amountTestId('price', 'gross')"
            @update:model-value="emit('purchasePriceChange', $event)"
          />
        </div>

        <div
          class="grid gap-2 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
        >
          <RadioGroup
            :model-value="form.purchaseActiveRow"
            class="items-center gap-2"
            @update:model-value="emit('purchaseActiveRowChange', $event as PurchaseActiveRow)"
          >
            <RadioGroupItem
              value="COST"
              :class="activeRowRadioClass"
              aria-label="Einkaufskosten aktiv"
            />
            <span class="font-medium text-foreground">Einkaufskosten</span>
          </RadioGroup>
          <PriceAmountField
            :model-value="fields.purchaseCost"
            :value="amountValue(price?.purchaseCost, 'net')"
            suffix="EUR"
            aria-label="Einkaufskosten netto"
            :editable="form.purchaseActiveRow === 'COST' && isModeColumn('net')"
            :test-id="amountTestId('cost', 'net')"
            @update:model-value="emit('purchaseCostChange', $event)"
          />
          <PriceAmountField
            :value="amountValue(price?.purchaseCost, 'tax')"
            suffix="EUR"
            aria-label="Einkaufskosten Steuer"
            disabled
            :test-id="amountTestId('cost', 'tax')"
          />
          <PriceAmountField
            :model-value="fields.purchaseCost"
            :value="amountValue(price?.purchaseCost, 'gross')"
            suffix="EUR"
            aria-label="Einkaufskosten brutto"
            :editable="form.purchaseActiveRow === 'COST' && isModeColumn('gross')"
            :test-id="amountTestId('cost', 'gross')"
            @update:model-value="emit('purchaseCostChange', $event)"
          />
        </div>

        <div
          class="grid gap-2 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
        >
          <RadioGroup
            :model-value="form.purchaseActiveRow"
            class="items-center gap-2"
            @update:model-value="emit('purchaseActiveRowChange', $event as PurchaseActiveRow)"
          >
            <RadioGroupItem
              value="COST_PERCENT"
              :class="activeRowRadioClass"
              aria-label="Einkaufskosten Prozent aktiv"
            />
            <span class="font-medium text-foreground">Einkaufskosten %</span>
          </RadioGroup>
          <PriceAmountField
            :model-value="fields.purchaseCostPercent"
            :value="percentValue(price?.calculatedPurchaseCostPercent)"
            suffix="%"
            aria-label="Einkaufskosten Prozent"
            :editable="form.purchaseActiveRow === 'COST_PERCENT'"
            :test-id="amountTestId('cost-percent', 'net')"
            @update:model-value="emit('purchaseCostPercentChange', $event)"
          />
          <div class="hidden md:block" />
          <div class="hidden md:block" />
        </div>

        <div
          class="grid gap-2 bg-muted/10 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
        >
          <div class="font-semibold text-foreground">Einkauf gesamt</div>
          <PriceAmountField
            :value="amountValue(price?.purchaseTotal, 'net')"
            suffix="EUR"
            aria-label="Einkauf gesamt netto"
            disabled
            :test-id="amountTestId('total', 'net')"
          />
          <PriceAmountField
            :value="amountValue(price?.purchaseTotal, 'tax')"
            suffix="EUR"
            aria-label="Einkauf gesamt Steuer"
            disabled
            :test-id="amountTestId('total', 'tax')"
          />
          <PriceAmountField
            :value="amountValue(price?.purchaseTotal, 'gross')"
            suffix="EUR"
            aria-label="Einkauf gesamt brutto"
            disabled
            :test-id="amountTestId('total', 'gross')"
          />
        </div>
      </template>

      <template v-else>
        <div
          class="grid gap-2 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
        >
          <RadioGroup
            :model-value="form.salesActiveRow"
            class="items-center gap-2"
            @update:model-value="emit('salesActiveRowChange', $event as SalesActiveRow)"
          >
            <RadioGroupItem value="MARGIN" :class="activeRowRadioClass" aria-label="Marge aktiv" />
            <span class="font-medium text-foreground">Marge</span>
          </RadioGroup>
          <PriceAmountField
            :model-value="fields.salesMargin"
            :value="amountValue(price?.salesMargin, 'net')"
            suffix="EUR"
            aria-label="Marge netto"
            :editable="form.salesActiveRow === 'MARGIN' && isModeColumn('net')"
            :test-id="amountTestId('margin', 'net')"
            @update:model-value="emit('salesMarginChange', $event)"
          />
          <PriceAmountField
            :value="amountValue(price?.salesMargin, 'tax')"
            suffix="EUR"
            aria-label="Marge Steuer"
            disabled
            :test-id="amountTestId('margin', 'tax')"
          />
          <PriceAmountField
            :model-value="fields.salesMargin"
            :value="amountValue(price?.salesMargin, 'gross')"
            suffix="EUR"
            aria-label="Marge brutto"
            :editable="form.salesActiveRow === 'MARGIN' && isModeColumn('gross')"
            :test-id="amountTestId('margin', 'gross')"
            @update:model-value="emit('salesMarginChange', $event)"
          />
        </div>

        <div
          class="grid gap-2 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
        >
          <RadioGroup
            :model-value="form.salesActiveRow"
            class="items-center gap-2"
            @update:model-value="emit('salesActiveRowChange', $event as SalesActiveRow)"
          >
            <RadioGroupItem
              value="MARGIN_PERCENT"
              :class="activeRowRadioClass"
              aria-label="Marge Prozent aktiv"
            />
            <span class="font-medium text-foreground">Marge %</span>
          </RadioGroup>
          <PriceAmountField
            :model-value="fields.salesMarginPercent"
            :value="percentValue(price?.calculatedSalesMarginPercent)"
            suffix="%"
            aria-label="Marge Prozent"
            :editable="form.salesActiveRow === 'MARGIN_PERCENT'"
            :test-id="amountTestId('margin-percent', 'net')"
            @update:model-value="emit('salesMarginPercentChange', $event)"
          />
          <div class="hidden md:block" />
          <div class="hidden md:block" />
        </div>

        <div
          class="grid gap-2 bg-primary/5 px-4 py-3 md:grid-cols-[minmax(12rem,1.5fr)_repeat(3,minmax(7rem,1fr))] md:items-center"
        >
          <RadioGroup
            :model-value="form.salesActiveRow"
            class="items-center gap-2"
            @update:model-value="emit('salesActiveRowChange', $event as SalesActiveRow)"
          >
            <RadioGroupItem
              value="TOTAL"
              :class="activeRowRadioClass"
              aria-label="Verkauf gesamt aktiv"
            />
            <span class="font-semibold text-foreground">Verkauf gesamt</span>
          </RadioGroup>
          <PriceAmountField
            :model-value="fields.salesTotal"
            :value="amountValue(price?.salesTotal, 'net')"
            suffix="EUR"
            aria-label="Verkauf gesamt netto"
            :editable="form.salesActiveRow === 'TOTAL' && isModeColumn('net')"
            :emphasis="form.salesActiveRow === 'TOTAL' && isModeColumn('net')"
            :test-id="amountTestId('total', 'net')"
            @update:model-value="emit('salesTotalChange', $event)"
          />
          <PriceAmountField
            :value="amountValue(price?.salesTotal, 'tax')"
            suffix="EUR"
            aria-label="Verkauf gesamt Steuer"
            disabled
            :test-id="amountTestId('total', 'tax')"
          />
          <PriceAmountField
            :model-value="fields.salesTotal"
            :value="amountValue(price?.salesTotal, 'gross')"
            suffix="EUR"
            aria-label="Verkauf gesamt brutto"
            :editable="form.salesActiveRow === 'TOTAL' && isModeColumn('gross')"
            :emphasis="form.salesActiveRow === 'TOTAL' && isModeColumn('gross')"
            :test-id="amountTestId('total', 'gross')"
            @update:model-value="emit('salesTotalChange', $event)"
          />
        </div>
      </template>
    </div>
  </section>
</template>
