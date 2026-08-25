import { computed, onScopeDispose, reactive, readonly, shallowRef } from 'vue'
import {
  buildPriceInputFromForm,
  createEmptyPriceForm,
  createFieldTextsFromForm,
  createPriceFormFromDto,
  formatCents,
  formatPercent,
  getModeAmount,
  parseGermanMoneyToCents,
  parseGermanPercent,
  validatePercentValue,
  MAX_DISCOUNT_PERCENT,
  type AdminPriceFieldTexts,
  type AdminPriceFormState,
  type PercentValidationError,
} from '@/lib/adminPrice'
import {
  calculatePrice,
  fetchDefaultPrice,
  type AdminPriceDto,
  type AdminPriceInputDto,
  type PriceCalculationMode,
  type PriceDiscountType,
  type PurchaseActiveRow,
  type SalesActiveRow,
} from '@/stores/admin/prices'
import { ApiError } from '@/lib/api'

export type AdminPricePersistence = 'optional' | 'required'

export interface UseAdminPriceFormOptions {
  persistence: AdminPricePersistence
}

type MoneyField = 'purchasePrice' | 'purchaseCost' | 'salesMargin' | 'salesTotal'
type PercentField = 'purchaseCostPercent' | 'salesMarginPercent'
type FieldName = MoneyField | PercentField | 'discountValue'

const CALCULATE_DEBOUNCE_MS = 350

const fieldLabels: Record<FieldName, string> = {
  purchasePrice: 'Purchase price',
  purchaseCost: 'Purchase costs',
  purchaseCostPercent: 'Purchase costs %',
  salesMargin: 'Margin',
  salesMarginPercent: 'Margin %',
  salesTotal: 'Sales total',
  discountValue: 'Discount',
}

function assignForm(target: AdminPriceFormState, source: AdminPriceFormState) {
  target.purchaseVatId = source.purchaseVatId
  target.purchaseCalculationMode = source.purchaseCalculationMode
  target.purchaseActiveRow = source.purchaseActiveRow
  target.purchasePriceInputCents = source.purchasePriceInputCents
  target.purchaseCostInputCents = source.purchaseCostInputCents
  target.purchaseCostPercent = source.purchaseCostPercent
  target.salesVatId = source.salesVatId
  target.salesCalculationMode = source.salesCalculationMode
  target.salesActiveRow = source.salesActiveRow
  target.salesMarginInputCents = source.salesMarginInputCents
  target.salesMarginPercent = source.salesMarginPercent
  target.salesTotalInputCents = source.salesTotalInputCents
  target.discountType = source.discountType
  target.discountValue = source.discountValue
}

function assignFieldTexts(target: AdminPriceFieldTexts, source: AdminPriceFieldTexts) {
  target.purchasePrice = source.purchasePrice
  target.purchaseCost = source.purchaseCost
  target.purchaseCostPercent = source.purchaseCostPercent
  target.salesMargin = source.salesMargin
  target.salesMarginPercent = source.salesMarginPercent
  target.salesTotal = source.salesTotal
  target.discountValue = source.discountValue
}

function percentErrorMessage(
  field: PercentField,
  violation: PercentValidationError,
  allowNegative: boolean,
) {
  if (violation === 'scale') {
    return `${fieldLabels[field]} must not have more than two decimal places.`
  }

  const minimum = allowNegative ? '-9.999,99' : '0'
  return `${fieldLabels[field]} must be between ${minimum} and 9.999,99.`
}

function readErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Price could not be calculated.'
}

/**
 * The rules the backend alone can check - a saving larger than the sales total, or a value the
 * form does not reject - come back as a field error on `discountValue`. It belongs on the discount
 * input, not in the summary above the form, whose message is the constant "Validation failed".
 */
function readDiscountValueError(error: unknown) {
  return error instanceof ApiError ? (error.fieldErrors.discountValue?.[0] ?? null) : null
}

export function useAdminPriceForm(options: UseAdminPriceFormOptions) {
  const form = reactive<AdminPriceFormState>(createEmptyPriceForm())
  const fields = reactive<AdminPriceFieldTexts>(createFieldTextsFromForm(form))
  const fieldErrors = reactive<Partial<Record<FieldName, string>>>({})
  const lastCalculatedPrice = shallowRef<AdminPriceDto | null>(null)
  const isLoading = shallowRef(false)
  const isCalculating = shallowRef(false)
  const error = shallowRef<string | null>(null)
  const setupError = shallowRef<string | null>(null)
  const isDirty = shallowRef(false)
  const hasExistingPrice = shallowRef(false)
  const isCalculationPending = shallowRef(false)

  let debounceHandle: ReturnType<typeof setTimeout> | null = null
  let calculateSequence = 0
  let initializeSequence = 0

  const inputError = computed(() => {
    const activeFields: FieldName[] = ['purchasePrice']
    activeFields.push(form.purchaseActiveRow === 'COST' ? 'purchaseCost' : 'purchaseCostPercent')
    activeFields.push(
      form.salesActiveRow === 'MARGIN'
        ? 'salesMargin'
        : form.salesActiveRow === 'MARGIN_PERCENT'
          ? 'salesMarginPercent'
          : 'salesTotal',
    )
    if (form.discountType !== null) {
      activeFields.push('discountValue')
    }

    return activeFields.map((field) => fieldErrors[field]).find(Boolean) ?? null
  })
  const hasVatIds = computed(() => form.purchaseVatId !== null && form.salesVatId !== null)
  const shouldPersistPrice = computed(
    () => options.persistence === 'required' || hasExistingPrice.value || isDirty.value,
  )
  const isValidForSave = computed(() => {
    if (!shouldPersistPrice.value) {
      return true
    }

    return (
      inputError.value === null &&
      error.value === null &&
      hasVatIds.value &&
      !isCalculationPending.value &&
      !isCalculating.value
    )
  })

  function clearDebounce() {
    if (debounceHandle !== null) {
      clearTimeout(debounceHandle)
      debounceHandle = null
    }
  }

  function resetErrors() {
    fieldErrors.purchasePrice = undefined
    fieldErrors.purchaseCost = undefined
    fieldErrors.purchaseCostPercent = undefined
    fieldErrors.salesMargin = undefined
    fieldErrors.salesMarginPercent = undefined
    fieldErrors.salesTotal = undefined
    fieldErrors.discountValue = undefined
    error.value = null
  }

  function clearFieldErrors(...fieldsToClear: FieldName[]) {
    for (const field of fieldsToClear) {
      fieldErrors[field] = undefined
    }
  }

  function resetState() {
    clearDebounce()
    calculateSequence += 1
    assignForm(form, createEmptyPriceForm())
    assignFieldTexts(fields, createFieldTextsFromForm(form))
    resetErrors()
    lastCalculatedPrice.value = null
    setupError.value = null
    isDirty.value = false
    hasExistingPrice.value = false
    isCalculationPending.value = false
    isLoading.value = false
    isCalculating.value = false
  }

  function applyCalculatedPrice(price: AdminPriceDto) {
    lastCalculatedPrice.value = price
    assignForm(form, createPriceFormFromDto(price))
    assignFieldTexts(fields, createFieldTextsFromForm(form))
    resetErrors()
  }

  async function initialize(initialPrice: AdminPriceDto | null) {
    const sequence = ++initializeSequence
    resetState()
    hasExistingPrice.value = initialPrice !== null

    if (initialPrice !== null) {
      applyCalculatedPrice(initialPrice)
      return
    }

    isLoading.value = true
    try {
      const defaultPrice = await fetchDefaultPrice()
      if (sequence !== initializeSequence) {
        return
      }

      applyCalculatedPrice(defaultPrice)
    } catch (err) {
      if (sequence !== initializeSequence) {
        return
      }

      setupError.value = readErrorMessage(err)
    } finally {
      if (sequence === initializeSequence) {
        isLoading.value = false
      }
    }
  }

  function toPayload() {
    return buildPriceInputFromForm(form)
  }

  async function runCalculate() {
    const payload = toPayload()
    if (payload === null || inputError.value !== null) {
      return
    }

    const sequence = ++calculateSequence
    isCalculating.value = true
    error.value = null

    try {
      const calculatedPrice = await calculatePrice(payload)
      if (sequence !== calculateSequence) {
        return
      }

      applyCalculatedPrice(calculatedPrice)
      isDirty.value = true
      isCalculationPending.value = false
    } catch (err) {
      if (sequence !== calculateSequence) {
        return
      }

      const discountValueError = readDiscountValueError(err)
      if (discountValueError === null) {
        error.value = readErrorMessage(err)
      } else {
        fieldErrors.discountValue = discountValueError
      }
    } finally {
      if (sequence === calculateSequence) {
        isCalculating.value = false
      }
    }
  }

  function scheduleCalculate() {
    clearDebounce()

    if (inputError.value !== null || !hasVatIds.value) {
      return
    }

    debounceHandle = setTimeout(() => {
      debounceHandle = null
      void runCalculate()
    }, CALCULATE_DEBOUNCE_MS)
  }

  function invalidateCurrentCalculation() {
    clearDebounce()
    calculateSequence += 1
    isCalculating.value = false
    isCalculationPending.value = true
  }

  function markDirtyAndCalculate() {
    isDirty.value = true
    invalidateCurrentCalculation()
    setupError.value = null
    error.value = null
    scheduleCalculate()
  }

  function setMoneyField(field: MoneyField, value: string) {
    fields[field] = value
    const cents = parseGermanMoneyToCents(value)
    if (cents === null) {
      rejectField(field, `${fieldLabels[field]} must be a valid decimal number.`)
      return
    }

    fieldErrors[field] = undefined
    if (field === 'purchasePrice') {
      form.purchasePriceInputCents = cents
    } else if (field === 'purchaseCost') {
      form.purchaseCostInputCents = cents
    } else if (field === 'salesMargin') {
      form.salesMarginInputCents = cents
    } else {
      form.salesTotalInputCents = cents
    }
    markDirtyAndCalculate()
  }

  function setPercentField(field: PercentField, value: string) {
    fields[field] = value
    const percent = parseGermanPercent(value)
    if (percent === null) {
      rejectField(field, `${fieldLabels[field]} must be a valid decimal number.`)
      return
    }

    const allowNegative = field === 'salesMarginPercent'
    const violation = validatePercentValue(percent, { allowNegative })
    if (violation !== null) {
      rejectField(field, percentErrorMessage(field, violation, allowNegative))
      return
    }

    fieldErrors[field] = undefined
    if (field === 'purchaseCostPercent') {
      form.purchaseCostPercent = percent
    } else {
      form.salesMarginPercent = percent
    }
    markDirtyAndCalculate()
  }

  /** Keeps a rejected input visible in its field and blocks the calculation it would send. */
  function rejectField(field: FieldName, message: string) {
    fieldErrors[field] = message
    invalidateCurrentCalculation()
    isDirty.value = true
  }

  /**
   * Switching the discount kind clears the value, because a percentage and a fixed amount are not
   * the same number. A kind without a value is not a payload the backend accepts, so the missing
   * value is a field error until the user types one.
   */
  function setDiscountType(type: PriceDiscountType | null) {
    if (form.discountType === type) {
      return
    }

    form.discountType = type
    form.discountValue = null
    fields.discountValue = ''
    fieldErrors.discountValue = type === null ? undefined : 'Discount value is required.'
    markDirtyAndCalculate()
  }

  /** A discount is the absent pair or a positive value; `0` is a value the backend rejects. */
  function setDiscountValue(value: string) {
    fields.discountValue = value

    if (value.trim() === '') {
      rejectField('discountValue', 'Discount value is required.')
      return
    }

    if (form.discountType === 'FIXED_AMOUNT') {
      const cents = parseGermanMoneyToCents(value)
      if (cents === null) {
        rejectField('discountValue', 'Discount must be a valid decimal number.')
        return
      }

      if (cents <= 0) {
        rejectField('discountValue', 'Discount must be greater than 0.')
        return
      }

      form.discountValue = cents
    } else {
      const percent = parseGermanPercent(value)
      if (percent === null) {
        rejectField('discountValue', 'Discount must be a valid decimal number.')
        return
      }

      if (percent <= 0) {
        rejectField('discountValue', 'Discount must be greater than 0.')
        return
      }

      const violation = validatePercentValue(percent, {
        allowNegative: false,
        maximum: MAX_DISCOUNT_PERCENT,
      })
      if (violation !== null) {
        rejectField(
          'discountValue',
          violation === 'scale'
            ? 'Discount must not have more than two decimal places.'
            : `Discount must be greater than 0 and at most ${MAX_DISCOUNT_PERCENT}.`,
        )
        return
      }

      form.discountValue = percent
    }

    fieldErrors.discountValue = undefined
    markDirtyAndCalculate()
  }

  function setPurchaseVatId(vatId: number | null) {
    form.purchaseVatId = vatId
    markDirtyAndCalculate()
  }

  function setSalesVatId(vatId: number | null) {
    form.salesVatId = vatId
    markDirtyAndCalculate()
  }

  function setPurchaseCalculationMode(mode: PriceCalculationMode) {
    if (form.purchaseCalculationMode === mode) {
      return
    }

    const price = lastCalculatedPrice.value
    form.purchaseCalculationMode = mode
    if (price !== null) {
      form.purchasePriceInputCents = getModeAmount(price.purchasePrice, mode)
      fields.purchasePrice = formatCents(form.purchasePriceInputCents)
      clearFieldErrors('purchasePrice')

      if (form.purchaseActiveRow === 'COST') {
        form.purchaseCostInputCents = getModeAmount(price.purchaseCost, mode)
        fields.purchaseCost = formatCents(form.purchaseCostInputCents)
        clearFieldErrors('purchaseCost')
      }
    }
    markDirtyAndCalculate()
  }

  function setSalesCalculationMode(mode: PriceCalculationMode) {
    if (form.salesCalculationMode === mode) {
      return
    }

    const price = lastCalculatedPrice.value
    form.salesCalculationMode = mode
    if (price !== null) {
      if (form.salesActiveRow === 'MARGIN') {
        form.salesMarginInputCents = getModeAmount(price.regularSalesMargin, mode)
        fields.salesMargin = formatCents(form.salesMarginInputCents)
        clearFieldErrors('salesMargin')
      }

      if (form.salesActiveRow === 'TOTAL') {
        form.salesTotalInputCents = getModeAmount(price.regularSalesTotal, mode)
        fields.salesTotal = formatCents(form.salesTotalInputCents)
        clearFieldErrors('salesTotal')
      }
    }
    markDirtyAndCalculate()
  }

  function setPurchaseActiveRow(row: PurchaseActiveRow) {
    if (form.purchaseActiveRow === row) {
      return
    }

    const price = lastCalculatedPrice.value
    form.purchaseActiveRow = row
    if (price !== null) {
      if (row === 'COST') {
        form.purchaseCostInputCents = getModeAmount(
          price.purchaseCost,
          form.purchaseCalculationMode,
        )
        fields.purchaseCost = formatCents(form.purchaseCostInputCents)
        clearFieldErrors('purchaseCost')
      } else {
        form.purchaseCostPercent = price.calculatedPurchaseCostPercent
        fields.purchaseCostPercent = formatPercent(form.purchaseCostPercent)
        clearFieldErrors('purchaseCostPercent')
      }
    }
    markDirtyAndCalculate()
  }

  function setSalesActiveRow(row: SalesActiveRow) {
    if (form.salesActiveRow === row) {
      return
    }

    const price = lastCalculatedPrice.value
    form.salesActiveRow = row
    if (price !== null) {
      if (row === 'MARGIN') {
        form.salesMarginInputCents = getModeAmount(
          price.regularSalesMargin,
          form.salesCalculationMode,
        )
        fields.salesMargin = formatCents(form.salesMarginInputCents)
        clearFieldErrors('salesMargin')
      } else if (row === 'MARGIN_PERCENT') {
        form.salesMarginPercent = price.calculatedRegularSalesMarginPercent
        fields.salesMarginPercent = formatPercent(form.salesMarginPercent)
        clearFieldErrors('salesMarginPercent')
      } else {
        form.salesTotalInputCents = getModeAmount(
          price.regularSalesTotal,
          form.salesCalculationMode,
        )
        fields.salesTotal = formatCents(form.salesTotalInputCents)
        clearFieldErrors('salesTotal')
      }
    }
    markDirtyAndCalculate()
  }

  function getSavePayload(): AdminPriceInputDto | undefined {
    if (!shouldPersistPrice.value || !isValidForSave.value) {
      return undefined
    }

    return toPayload() ?? undefined
  }

  function validateForSave() {
    if (isValidForSave.value) {
      return true
    }

    if (inputError.value !== null) {
      return false
    }

    if (!hasVatIds.value) {
      error.value = 'Purchase and sales VAT rates must be set before the price can be calculated.'
      return false
    }

    if (error.value === null) {
      error.value =
        isCalculationPending.value || isCalculating.value
          ? 'The current price calculation has not finished yet.'
          : 'The price calculation is not valid yet.'
    }

    return false
  }

  function markClean() {
    isDirty.value = false
  }

  async function calculateNow() {
    clearDebounce()
    await runCalculate()
  }

  onScopeDispose(() => {
    clearDebounce()
    calculateSequence += 1
    initializeSequence += 1
  })

  return {
    form: readonly(form),
    fields: readonly(fields),
    fieldErrors: readonly(fieldErrors),
    lastCalculatedPrice: readonly(lastCalculatedPrice),
    isLoading: readonly(isLoading),
    isCalculating: readonly(isCalculating),
    isCalculationPending: readonly(isCalculationPending),
    error: readonly(error),
    setupError: readonly(setupError),
    inputError,
    isDirty: readonly(isDirty),
    hasExistingPrice: readonly(hasExistingPrice),
    shouldPersistPrice,
    isValidForSave,
    initialize,
    calculateNow,
    markClean,
    validateForSave,
    getSavePayload,
    setPurchaseVatId,
    setSalesVatId,
    setPurchaseCalculationMode,
    setSalesCalculationMode,
    setPurchaseActiveRow,
    setSalesActiveRow,
    setPurchasePrice: (value: string) => setMoneyField('purchasePrice', value),
    setPurchaseCost: (value: string) => setMoneyField('purchaseCost', value),
    setPurchaseCostPercent: (value: string) => setPercentField('purchaseCostPercent', value),
    setSalesMargin: (value: string) => setMoneyField('salesMargin', value),
    setSalesMarginPercent: (value: string) => setPercentField('salesMarginPercent', value),
    setSalesTotal: (value: string) => setMoneyField('salesTotal', value),
    setDiscountType,
    setDiscountValue,
  }
}

export type AdminPriceFormController = ReturnType<typeof useAdminPriceForm>
