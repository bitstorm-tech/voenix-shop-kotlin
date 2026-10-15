import type { ExampleImages } from '@/modules/image'
import type { PriceCatalog, PriceInput } from '@/modules/pricing'
import type { SupplierModule } from '@/modules/supplier'
import { conflict, invalid, notFound, ok, type OperationResult } from '@/platform/operation'
import { preparePrice } from '../price'
import type {
  SpodArticle,
  SpodArticleListItem,
  SpodArticleRepository,
  StoredSpodArticle,
} from './repository'

/**
 * The update body: the shop's half of a synced article and nothing else (SPOD sync decision 2).
 * `price: null` keeps the stored price.
 */
export interface SpodArticleInput {
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  defaultVariantId: number | null
  price: PriceInput | null
}

/** The three folders a SPOD article's pictures live in; the sync stores them, a delete removes them. */
export interface SpodPictureFolders {
  listingImages: ExampleImages
  mockups: ExampleImages
  sizeCharts: ExampleImages
}

export type SpodArticleService = ReturnType<typeof createSpodArticleService>

export function createSpodArticleService(
  repository: SpodArticleRepository,
  folders: SpodPictureFolders,
  prices: Pick<PriceCatalog, 'prepare' | 'find'>,
  suppliers: SupplierModule['find'],
) {
  /** The stored article with its price, recalculated from the current VAT entries. */
  const withPrice = async ({ article, priceId }: StoredSpodArticle): Promise<SpodArticle> =>
    priceId === null
      ? article
      : { ...article, price: (await prices.find([priceId])).get(priceId) ?? null }

  /** The list rows labeled with supplier names from one batched lookup. */
  const withSupplierNames = async (items: SpodArticleListItem[]) => {
    const names = await suppliers(items.map((item) => item.supplierId))
    return items.map((item) => ({
      ...item,
      supplierName: names.get(item.supplierId)?.name ?? null,
    }))
  }

  return {
    list: async (): Promise<OperationResult<SpodArticleListItem[]>> =>
      ok(await withSupplierNames(await repository.list())),

    get: async (id: number): Promise<OperationResult<SpodArticle>> => {
      const stored = await repository.find(id)
      return stored === undefined ? notFound : ok(await withPrice(stored))
    },

    update: async (id: number, input: SpodArticleInput): Promise<OperationResult<SpodArticle>> => {
      const price = await preparePrice(prices, input.price)
      if (price.kind !== 'ok') return price
      const result = await repository.update(id, input, price.value)
      switch (result) {
        case 'notFound':
          return notFound
        case 'categoryNotFound':
          return invalid({ categoryId: ['Article category does not exist'] })
        case 'subcategoryNotFound':
          return invalid({
            subcategoryId: ['Article subcategory does not exist in this article category'],
          })
        case 'priceRequired':
          return invalid({ price: ['An active article requires a price'] })
        case 'unknownVariant':
          return invalid({
            defaultVariantId: ['The default variant is not an active variant of this article'],
          })
        case 'missingAtSpreadconnect':
          return invalid({
            active: ['An article that is missing at Spreadconnect cannot be activated'],
          })
        case 'frameNotMeasured':
          return invalid({
            active: ['An article without a measured print frame cannot be activated'],
          })
        default:
          return ok(await withPrice(result))
      }
    },

    delete: async (id: number): Promise<OperationResult<undefined>> => {
      const deleted = await repository.delete(id)
      if (deleted === 'notFound') return notFound
      for (const filename of deleted.listingImages)
        await folders.listingImages.deleteObsolete(filename)
      for (const filename of deleted.mockups) await folders.mockups.deleteObsolete(filename)
      for (const filename of deleted.sizeCharts) await folders.sizeCharts.deleteObsolete(filename)
      return ok(undefined)
    },

    reorder: async (
      sourceId: number,
      targetId: number,
    ): Promise<OperationResult<SpodArticleListItem[]>> => {
      const result = await repository.reorder(sourceId, targetId)
      if (result === 'notFound') return notFound
      if (result === 'conflict') return conflict
      return ok(await withSupplierNames(result))
    },
  }
}
