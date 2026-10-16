import type { ExampleImages } from '@/modules/image'
import type { CalculatedPrice, PriceCatalog, PriceInput } from '@/modules/pricing'
import type { SupplierModule } from '@/modules/supplier'
import {
  invalid,
  notFound,
  ok,
  type OperationResult,
  type ValidationErrors,
} from '@/platform/operation'
import type { PrintAspectRatio } from '../catalog'
import { preparePrice } from '../price'
import type {
  MugArticle,
  MugArticleListItem,
  MugDetails,
  MugRepository,
  StoredMug,
} from './repository'

/**
 * The create and update body, normalized by the schema: texts trimmed, blank optional texts `null`,
 * an absent ratio `16:9`. `price: null` keeps the stored price on an update.
 */
export interface MugArticleInput {
  name: string
  descriptionShort: string
  descriptionLong: string
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  supplierId: number | null
  supplierArticleName: string | null
  supplierArticleNumber: string | null
  printAspectRatio: PrintAspectRatio
  mugDetails: MugDetails | null
  mugVariants: MugVariantInput[]
  price: PriceInput | null
}

/**
 * One entry of the variant array, which is a diff: an id updates that variant, no id inserts one,
 * and a stored variant the array does not mention is deleted with its example image.
 */
export interface MugVariantInput {
  id: number | null
  name: string
  insideColorCode: string
  outsideColorCode: string
  isDefault: boolean
  active: boolean
  exampleImageFilename: string | null
}

export type MugService = ReturnType<typeof createMugService>

/** Every outcome of a mug write; an insert answers a subset of it. */
type MugWriteResult = Awaited<ReturnType<MugRepository['update']>>

/**
 * The admin lifecycle of a mug. Before the transaction opens, the submitted example images are
 * checked against the storage and the price is prepared; only the writes run in one transaction,
 * which the price write joins. Files a write orphaned are deleted after the commit.
 */
export function createMugService(
  repository: MugRepository,
  exampleImages: ExampleImages,
  prices: Pick<PriceCatalog, 'prepare' | 'find'>,
  suppliers: SupplierModule['find'],
) {
  const withPrice = async ({ article, priceId }: StoredMug): Promise<MugArticle> =>
    priceId === null
      ? article
      : { ...article, price: (await prices.find([priceId])).get(priceId) ?? null }

  /** The list rows labeled with supplier names from one batched lookup. */
  const withSupplierNames = async (items: MugArticleListItem[]) => {
    const names = await suppliers(items.flatMap((item) => item.supplierId ?? []))
    return items.map((item) => ({
      ...item,
      supplierName: item.supplierId === null ? null : (names.get(item.supplierId)?.name ?? null),
    }))
  }

  /**
   * Every submitted example image is checked, including one the variant already stores: a stored
   * name whose file is gone means another writer replaced it, and writing it back would point the
   * row at nothing.
   */
  const checkExampleImages = async (variants: readonly MugVariantInput[]) => {
    const errors: ValidationErrors = {}
    for (const [index, variant] of variants.entries()) {
      const checked = await exampleImages.checkSubmitted(
        `mugVariants[${String(index)}].exampleImageFilename`,
        variant.exampleImageFilename,
      )
      if (checked.kind === 'invalid') Object.assign(errors, checked.errors)
    }
    return errors
  }

  const writePrepared = async (
    input: MugArticleInput,
    write: (price: CalculatedPrice | null) => Promise<MugWriteResult>,
  ): Promise<OperationResult<MugArticle>> => {
    const imageErrors = await checkExampleImages(input.mugVariants)
    if (Object.keys(imageErrors).length > 0) return invalid(imageErrors)
    const price = await preparePrice(prices, input.price)
    if (price.kind !== 'ok') return price
    const result = await write(price.value)
    switch (result) {
      case 'notFound':
        return notFound
      case 'categoryNotFound':
        return invalid({ categoryId: ['Article category does not exist'] })
      case 'subcategoryNotFound':
        return invalid({
          subcategoryId: ['Article subcategory does not exist in this article category'],
        })
      case 'supplierNotFound':
        return invalid({ supplierId: ['Supplier does not exist'] })
      case 'priceRequired':
        return invalid({ price: ['An active article requires a price'] })
      case 'unknownVariant':
        return invalid({ mugVariants: ['One or more variants do not belong to this article'] })
      default:
        for (const filename of result.obsoleteFilenames) {
          await exampleImages.deleteObsolete(filename)
        }
        return ok(await withPrice(result.mug))
    }
  }

  return {
    list: async (): Promise<MugArticleListItem[]> => withSupplierNames(await repository.list()),

    get: async (id: number): Promise<OperationResult<MugArticle>> => {
      const stored = await repository.find(id)
      return stored === undefined ? notFound : ok(await withPrice(stored))
    },

    create: (input: MugArticleInput) =>
      writePrepared(input, (price) => repository.insert(input, price)),

    update: (id: number, input: MugArticleInput) =>
      writePrepared(input, (price) => repository.update(id, input, price)),

    delete: async (id: number): Promise<OperationResult<undefined>> => {
      const deleted = await repository.delete(id)
      if (deleted.kind !== 'ok') return deleted
      for (const filename of deleted.value) await exampleImages.deleteObsolete(filename)
      return ok(undefined)
    },

    reorder: async (
      sourceId: number,
      targetId: number,
    ): Promise<OperationResult<MugArticleListItem[]>> => {
      const result = await repository.reorder(sourceId, targetId)
      if (result.kind !== 'ok') return result
      return ok(await withSupplierNames(result.value))
    },

    storeExampleImage: exampleImages.store,
  }
}
