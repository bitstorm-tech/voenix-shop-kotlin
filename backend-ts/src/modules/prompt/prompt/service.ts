import type { ExampleImages, ImageUpload } from '@/modules/image'
import type { CalculatedPrice, PriceCatalog, PriceInput } from '@/modules/pricing'
import {
  conflict,
  invalid,
  notFound,
  ok,
  type OperationFailure,
  type OperationResult,
} from '@/platform/operation'
import type {
  CategoryReference,
  PromptRepository,
  PromptValues,
  PromptWrite,
  StoredListRow,
  StoredPrompt,
} from './repository'

/** The validated create and update body, as `routes.ts`'s schema produces it. */
export interface PromptInput {
  title: string
  promptText: string
  categoryId: number
  subcategoryId: number | null
  slotVariantIds: number[]
  exampleImageFilename: string | null
  llm: string | null
  active: boolean
  archived: boolean
  price: PriceInput
}

/**
 * What a list row shows of a price: the *effective* sales total (a discount already subtracted),
 * the gross before the discount exactly when there is one, and the VAT rate.
 */
export interface PromptPrice {
  salesTotalNet: number
  salesTotalGross: number
  salesTotalTax: number
  regularSalesTotalGross: number | null
  salesVatRatePercent: number
}

/** The admin representation: get, create, and update answer it. */
export type Prompt = Omit<StoredPrompt, 'priceId'> & { price: CalculatedPrice | null }
/** One row of the admin list and of the reorder answer. */
export type PromptListItem = Omit<StoredListRow, 'priceId'> & { price: PromptPrice | null }

/** One prompt of the storefront list: no prompt text, ever. */
export interface PublicPrompt {
  id: number
  position: number
  title: string
  category: CategoryReference
  subcategory: CategoryReference | null
  exampleImageFilename: string | null
  llm: string | null
  price: PromptPrice | null
}

export type PromptService = ReturnType<typeof createPromptService>

const EXAMPLE_IMAGE_FIELD = 'exampleImageFilename'

/**
 * The admin lifecycle of a prompt and the storefront read. The example image is checked and the
 * price prepared *before* the write transaction opens; neither touches this database connection.
 * Every read resolves its prices in one batched `priceCatalog.find`, and in none for a page without
 * a price.
 */
export function createPromptService({
  repository,
  priceCatalog,
  exampleImages,
}: {
  repository: PromptRepository
  priceCatalog: PriceCatalog
  exampleImages: ExampleImages
}) {
  /** The price of each row, as `project` shapes it. */
  async function withPrices<Row extends { priceId: number | null }, Price>(
    rows: Row[],
    project: (price: CalculatedPrice) => Price,
  ): Promise<(Omit<Row, 'priceId'> & { price: Price | null })[]> {
    const priceIds = rows.flatMap((row) => (row.priceId === null ? [] : [row.priceId]))
    const prices =
      priceIds.length === 0 ? new Map<number, CalculatedPrice>() : await priceCatalog.find(priceIds)
    return rows.map(({ priceId, ...row }) => {
      const price = priceId === null ? undefined : prices.get(priceId)
      return { ...row, price: price === undefined ? null : project(price) }
    })
  }

  async function withPrice(stored: StoredPrompt): Promise<Prompt> {
    const [prompt] = await withPrices([stored], (price) => price)
    if (prompt === undefined) throw new Error('A prompt lost its row while its price was read')
    return prompt
  }

  /**
   * Checks the submitted example image, prepares the price, runs the write, and deletes the image
   * the write left unnamed, after the commit.
   */
  async function writePrepared(
    { price: priceInput, ...values }: PromptInput,
    write: (
      values: PromptValues,
      price: CalculatedPrice,
    ) => Promise<
      PromptWrite | 'notFound' | 'categoryNotFound' | 'subcategoryNotFound' | 'slotVariantNotFound'
    >,
  ): Promise<OperationResult<Prompt>> {
    const image = await exampleImages.checkSubmitted(
      EXAMPLE_IMAGE_FIELD,
      values.exampleImageFilename,
    )
    if (image.kind !== 'ok') return image
    const price = await priceCatalog.prepare(priceInput)
    if (price.kind !== 'ok') return underPrice(price)
    const written = await write(values, price.value)
    switch (written) {
      case 'notFound':
        return notFound
      case 'categoryNotFound':
        return invalid({ categoryId: ['Prompt category does not exist'] })
      case 'subcategoryNotFound':
        return invalid({
          subcategoryId: ['Prompt subcategory does not exist in this prompt category'],
        })
      case 'slotVariantNotFound':
        return invalid({ slotVariantIds: ['One or more prompt slot variants do not exist'] })
      default:
        await exampleImages.deleteObsolete(written.obsoleteExampleImage)
        return ok(await withPrice(written.prompt))
    }
  }

  return {
    list: async (): Promise<OperationResult<PromptListItem[]>> =>
      ok(await withPrices(await repository.list(), promptPrice)),

    get: async (id: number): Promise<OperationResult<Prompt>> => {
      const stored = await repository.find(id)
      return stored === undefined ? notFound : ok(await withPrice(stored))
    },

    create: (input: PromptInput) => writePrepared(input, repository.insert),

    update: (id: number, input: PromptInput) =>
      writePrepared(input, (values, price) => repository.update(id, values, price)),

    /** `conflict` is the one race a prompt write can lose: two admins moving prompts at once. */
    reorder: async (
      sourceId: number,
      targetId: number,
    ): Promise<OperationResult<PromptListItem[]>> => {
      const result = await repository.reorder(sourceId, targetId)
      if (result === 'notFound') return notFound
      if (result === 'conflict') return conflict
      return ok(await withPrices(result, promptPrice))
    },

    storeExampleImage: (upload: ImageUpload) => exampleImages.store(upload),

    listPublic: async (categoryId: number | null): Promise<PublicPrompt[]> =>
      withPrices(await repository.listPublic(categoryId), promptPrice),
  }
}

/**
 * The capability other modules use. Neither answer reads the category `active` flags: a prompt in
 * a deactivated category disappears from the storefront but stays generatable and buyable by id.
 */
export function createPromptCatalog({
  repository,
  priceCatalog,
}: {
  repository: PromptRepository
  priceCatalog: PriceCatalog
}) {
  return {
    /**
     * The generation text: the prompt's own text, then the text of every slot variant, each
     * trimmed, blank variants dropped, joined by a blank line. `null` when the prompt is unknown,
     * inactive, archived, or its text is blank.
     */
    composedText: async (promptId: number): Promise<string | null> => {
      const stored = await repository.composition(promptId)
      if (stored === undefined || stored.promptText.trim() === '') return null
      return [stored.promptText, ...stored.variantTexts]
        .map((text) => text.trim())
        .filter((text) => text !== '')
        .join('\n\n')
    },

    /**
     * The effective gross sales price in cents of every prompt that is active, not archived, and
     * linked to a price; every other id is absent, never `0`, which a shop may legitimately charge.
     */
    findSalesGrossPriceCents: async (promptIds: Iterable<number>): Promise<Map<number, number>> => {
      const ids = [...promptIds]
      if (ids.length === 0) return new Map()
      const priceIds = await repository.priceIds(ids)
      if (priceIds.size === 0) return new Map()
      const prices = await priceCatalog.find(priceIds.values())
      return new Map(
        [...priceIds].flatMap(([promptId, priceId]) => {
          const price = prices.get(priceId)
          return price === undefined ? [] : [[promptId, price.salesTotal.gross] as const]
        }),
      )
    },
  }
}

const promptPrice = (price: CalculatedPrice): PromptPrice => ({
  salesTotalNet: price.salesTotal.net,
  salesTotalGross: price.salesTotal.gross,
  salesTotalTax: price.salesTotal.tax,
  regularSalesTotalGross: price.discount === null ? null : price.regularSalesTotal.gross,
  salesVatRatePercent: price.salesVat.percent,
})

/** The price's field errors keep the path the client sent them at: `price.salesVatId`. */
function underPrice(failure: OperationFailure): OperationFailure {
  if (failure.kind !== 'invalid') return failure
  return {
    kind: 'invalid',
    errors: Object.fromEntries(
      Object.entries(failure.errors).map(([field, messages]) => [`price.${field}`, messages]),
    ),
  }
}
