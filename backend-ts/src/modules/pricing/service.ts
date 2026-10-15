import {
  buildValidationErrors,
  conflict,
  invalid,
  notFound,
  ok,
  type OperationResult,
} from '@/platform/operation'
import { calculatePrice, type CalculatedPrice, type PriceInput } from './calculation'
import type { PricingRepository, Vat } from './repository'

/** The validated body of a VAT create or update, as `vat-routes.ts`'s schema produces it. */
export type VatInput = Omit<Vat, 'id'>

export type PriceService = ReturnType<typeof createPriceService>
export type VatService = ReturnType<typeof createVatService>

export function createPriceService(repository: PricingRepository) {
  return {
    /**
     * Resolves both VAT entries in one lookup and calculates; nothing is stored. Answers `invalid`
     * for an unknown VAT, a negative regular sales total, and a discount that does not fit the
     * price. The field rules are the request schema's (`priceInput` in `routes.ts`).
     */
    prepare: async (input: PriceInput): Promise<OperationResult<CalculatedPrice>> => {
      const vats = await repository.findVats([input.purchaseVatId, input.salesVatId])
      const purchaseVat = vats.get(input.purchaseVatId)
      const salesVat = vats.get(input.salesVatId)
      if (purchaseVat === undefined || salesVat === undefined) {
        return invalid(
          buildValidationErrors((add) => {
            if (purchaseVat === undefined) add('purchaseVatId', 'Purchase VAT not found')
            if (salesVat === undefined) add('salesVatId', 'Sales VAT not found')
          }),
        )
      }
      return checkWriteRules(calculatePrice(null, zeroInactiveFields(input), purchaseVat, salesVat))
    },

    /**
     * The prices with these ids, recalculated with the current VAT values; unknown ids are absent.
     * One query for the prices and one for every referenced VAT entry. A read never re-validates:
     * a stored price a later VAT change put out of the write rules still answers what it costs.
     */
    find: async (ids: Iterable<number>): Promise<Map<number, CalculatedPrice>> => {
      const stored = await repository.findPrices(ids)
      if (stored.size === 0) return new Map()
      const vats = await repository.findVats(
        [...stored.values()].flatMap((input) => [input.purchaseVatId, input.salesVatId]),
      )
      return new Map(
        [...stored].map(([id, input]) => [
          id,
          calculatePrice(
            id,
            input,
            vatOf(vats, input.purchaseVatId),
            vatOf(vats, input.salesVatId),
          ),
        ]),
      )
    },

    /**
     * An all-zero price on the default VAT, or on the one with the smallest id when none is
     * marked; `undefined` when no VAT exists.
     */
    defaultPrice: async (): Promise<CalculatedPrice | undefined> => {
      const vats = await repository.listVats()
      const vat =
        vats.find((candidate) => candidate.isDefault) ?? vats.toSorted((a, b) => a.id - b.id)[0]
      if (vat === undefined) return undefined
      return calculatePrice(
        null,
        {
          purchaseVatId: vat.id,
          purchaseCalculationMode: 'NET',
          purchaseActiveRow: 'COST',
          purchasePriceInputCents: 0,
          purchaseCostInputCents: 0,
          purchaseCostPercent: 0,
          salesVatId: vat.id,
          salesCalculationMode: 'GROSS',
          salesActiveRow: 'TOTAL',
          salesMarginInputCents: 0,
          salesMarginPercent: 0,
          salesTotalInputCents: 0,
          discountType: null,
          discountValue: null,
        },
        vat,
        vat,
      )
    },
  }
}

export function createVatService(repository: PricingRepository) {
  return {
    list: async (): Promise<OperationResult<Vat[]>> => ok(await repository.listVats()),

    get: async (id: number): Promise<OperationResult<Vat>> => {
      const vat = await repository.findVat(id)
      return vat === undefined ? notFound : ok(vat)
    },

    create: async (input: VatInput): Promise<OperationResult<Vat>> => {
      const stored = await repository.insertVat(input)
      return stored === 'conflict' ? conflict : ok(stored)
    },

    update: async (id: number, input: VatInput): Promise<OperationResult<Vat>> => {
      const stored = await repository.updateVat(id, input)
      if (stored === 'conflict') return conflict
      if (stored === 'notFound') return notFound
      return ok(stored)
    },

    /** `conflict` means a price still references the entry. */
    delete: async (id: number): Promise<OperationResult<undefined>> => {
      const deleted = await repository.deleteVat(id)
      if (deleted === 'inUse') return conflict
      return deleted === 0 ? notFound : ok(undefined)
    },
  }
}

/** Inactive inputs are stored as zero, so the same price always produces the same row. */
function zeroInactiveFields(input: PriceInput): PriceInput {
  return {
    ...input,
    purchaseCostInputCents: input.purchaseActiveRow === 'COST' ? input.purchaseCostInputCents : 0,
    purchaseCostPercent: input.purchaseActiveRow === 'COST_PERCENT' ? input.purchaseCostPercent : 0,
    salesMarginInputCents: input.salesActiveRow === 'MARGIN' ? input.salesMarginInputCents : 0,
    salesMarginPercent: input.salesActiveRow === 'MARGIN_PERCENT' ? input.salesMarginPercent : 0,
    salesTotalInputCents: input.salesActiveRow === 'TOTAL' ? input.salesTotalInputCents : 0,
  }
}

const SALES_INPUT_FIELDS = {
  MARGIN: 'salesMarginInputCents',
  MARGIN_PERCENT: 'salesMarginPercent',
  TOTAL: 'salesTotalInputCents',
} as const

/**
 * The write rules the request schema cannot check, because they depend on the VAT and the active
 * sales row: the regular total must not be negative, a fixed discount must not exceed the regular
 * gross (the calculator caps the saving, so the submitted amount is compared), and a discount must
 * save at least one cent.
 */
function checkWriteRules(price: CalculatedPrice): OperationResult<CalculatedPrice> {
  const regular = price.regularSalesTotal
  if (regular.net < 0 || regular.gross < 0) {
    return invalid({
      [SALES_INPUT_FIELDS[price.salesActiveRow]]: ['Sales total must not be negative'],
    })
  }
  const discount = price.discount
  if (discount?.discountType === 'FIXED_AMOUNT' && discount.discountValue > regular.gross) {
    return invalid({ discountValue: ['Discount must not exceed the sales total'] })
  }
  if (discount !== null && price.salesDiscount.gross === 0) {
    return invalid({ discountValue: ['Discount must reduce the sales total'] })
  }
  return ok(price)
}

/** A stored price's VAT; the restricted foreign keys guarantee it exists. */
function vatOf(vats: Map<number, Vat>, id: number): Vat {
  const vat = vats.get(id)
  if (vat === undefined) throw new Error(`VAT ${String(id)} of a stored price is missing`)
  return vat
}
