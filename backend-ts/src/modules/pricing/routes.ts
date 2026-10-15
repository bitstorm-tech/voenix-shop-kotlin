import { Hono } from 'hono'
import { z } from 'zod'
import { requireAdmin } from '@/platform/auth/auth'
import { apiError, jsonBody, respondResult } from '@/platform/http/respond'
import {
  PRICE_CALCULATION_MODES,
  PRICE_DISCOUNT_TYPES,
  PURCHASE_ACTIVE_ROWS,
  SALES_ACTIVE_ROWS,
  type PriceDiscountType,
  type PriceInput,
} from './calculation'
import type { PriceService } from './service'

const MAXIMUM_PERCENT = 9999.99

const cents = z.int32().default(0)
const percent = z.number().default(0)
/** A missing, `null`, or non-integer VAT id answers the required message like an id below 1. */
const vatId = z.int().catch(0)

/** At most two decimals, read from the value scaled to hundredths so `0.07` and `12.340` pass. */
const hasTooManyDecimals = (value: number) =>
  !Number.isInteger(Number((value * 100).toPrecision(15)))

const discountTypeOf = (value: string | null): PriceDiscountType | null =>
  PRICE_DISCOUNT_TYPES.find((type) => type === value) ?? null

/**
 * The price body of `POST /api/admin/prices/calculate`, and the `price` another module embeds in
 * its own request. Omitted fields take the defaults below. Only the active row's input is checked;
 * the service stores the inactive ones as zero. The discount type stays a string until the rules
 * have run, so an unknown value is a field error with its own message.
 */
export const priceInput = z
  .strictObject({
    purchaseVatId: vatId,
    purchaseCalculationMode: z.enum(PRICE_CALCULATION_MODES).default('NET'),
    purchaseActiveRow: z.enum(PURCHASE_ACTIVE_ROWS).default('COST'),
    purchasePriceInputCents: cents,
    purchaseCostInputCents: cents,
    purchaseCostPercent: percent,
    salesVatId: vatId,
    salesCalculationMode: z.enum(PRICE_CALCULATION_MODES).default('GROSS'),
    salesActiveRow: z.enum(SALES_ACTIVE_ROWS).default('TOTAL'),
    salesMarginInputCents: cents,
    salesMarginPercent: percent,
    salesTotalInputCents: cents,
    discountType: z.string().nullable().default(null),
    discountValue: z.number().nullable().default(null),
  })
  .superRefine((input, ctx) => {
    const add = (field: string, message: string) => {
      ctx.addIssue({ code: 'custom', path: [field], message })
    }
    if (input.purchaseVatId <= 0) add('purchaseVatId', 'Purchase VAT id is required')
    if (input.salesVatId <= 0) add('salesVatId', 'Sales VAT id is required')
    if (input.purchasePriceInputCents < 0) {
      add('purchasePriceInputCents', 'Purchase price input must not be negative')
    }
    if (input.purchaseActiveRow === 'COST') {
      if (input.purchaseCostInputCents < 0) {
        add('purchaseCostInputCents', 'Purchase cost input must not be negative')
      }
    } else if (input.purchaseCostPercent < 0) {
      add('purchaseCostPercent', 'Purchase cost percent must not be negative')
    } else if (hasTooManyDecimals(input.purchaseCostPercent)) {
      add('purchaseCostPercent', 'Purchase cost percent must have at most two decimal places')
    } else if (input.purchaseCostPercent > MAXIMUM_PERCENT) {
      add('purchaseCostPercent', 'Purchase cost percent must not exceed 9999.99')
    }
    if (input.salesActiveRow === 'MARGIN_PERCENT') {
      if (hasTooManyDecimals(input.salesMarginPercent)) {
        add('salesMarginPercent', 'Sales margin percent must have at most two decimal places')
      } else if (Math.abs(input.salesMarginPercent) > MAXIMUM_PERCENT) {
        add('salesMarginPercent', 'Sales margin percent must be between -9999.99 and 9999.99')
      }
    }
    if (input.salesActiveRow === 'TOTAL' && input.salesTotalInputCents < 0) {
      add('salesTotalInputCents', 'Sales total input must not be negative')
    }
    addDiscountErrors(input.discountType, input.discountValue, add)
  })
  .transform((input) => ({
    ...input,
    discountType: discountTypeOf(input.discountType),
  })) satisfies z.ZodType<PriceInput>

function addDiscountErrors(
  discountType: string | null,
  value: number | null,
  add: (field: string, message: string) => void,
): void {
  if (discountType === null) {
    if (value !== null) add('discountType', 'Discount type is required')
    return
  }
  if (value === null) {
    add('discountValue', 'Discount value is required')
    return
  }
  const type = discountTypeOf(discountType)
  if (type === null) {
    add('discountType', 'Discount type must be PERCENTAGE or FIXED_AMOUNT')
  } else if (value <= 0) {
    add('discountValue', 'Discount value must be positive')
  } else if (type === 'PERCENTAGE' && value > 100) {
    add('discountValue', 'Discount value must be at most 100 for a percentage discount')
  } else if (type === 'PERCENTAGE' && hasTooManyDecimals(value)) {
    add('discountValue', 'Discount value must have at most two decimal places')
  } else if (type === 'FIXED_AMOUNT' && !Number.isInteger(value)) {
    add('discountValue', 'Discount value must be whole cents for a fixed amount discount')
  }
}

// `prepare` answers `ok` or `invalid` only; the messages complete the route file's contract.
const PRICE_RESPONSES = { notFound: 'Price not found', conflict: null }

export function priceRoutes(prices: PriceService): Hono {
  const admin = new Hono()
  admin.use(requireAdmin)
  admin.post('/calculate', jsonBody(priceInput), async (c) =>
    respondResult(c, await prices.prepare(c.req.valid('json')), PRICE_RESPONSES),
  )
  admin.get('/default', async (c) => {
    const price = await prices.defaultPrice()
    return price === undefined ? c.json(apiError('No VAT is configured'), 400) : c.json(price)
  })

  const routes = new Hono()
  routes.route('/api/admin/prices', admin)
  return routes
}
