import type { ExampleImages } from '@/modules/image'
import { conflict, invalid, notFound, ok, type OperationResult } from '@/platform/operation'
import type { ArticleCategory, ArticleSubcategory, CategoryRepository } from './repository'

/** The category create and update body, trimmed by the schema; a blank description is `null`. */
export interface ArticleCategoryInput {
  name: string
  description: string | null
  active: boolean
}

/** The subcategory create and update body; `exampleImageFilename` is a name a pre-upload answered. */
export interface ArticleSubcategoryInput {
  categoryId: number
  name: string
  description: string | null
  exampleImageFilename: string | null
  active: boolean
}

export type CategoryService = ReturnType<typeof createCategoryService>

/**
 * The category structure. Every result maps the repository's answer; the subcategory half also
 * owns the example-image rule: a submitted name is checked before the write, and a name no row
 * refers to any more is deleted after the commit.
 */
export function createCategoryService(
  repository: CategoryRepository,
  exampleImages: ExampleImages,
) {
  /** Checks the submitted image, then writes, then removes the file the write orphaned. */
  const writeSubcategory = async (
    input: ArticleSubcategoryInput,
    write: () => ReturnType<CategoryRepository['updateSubcategory']>,
  ): Promise<OperationResult<ArticleSubcategory>> => {
    const checked = await exampleImages.checkSubmitted(
      'exampleImageFilename',
      input.exampleImageFilename,
    )
    if (checked.kind !== 'ok') return checked
    const result = await write()
    switch (result) {
      case 'notFound':
        return notFound
      case 'conflict':
        return conflict
      case 'categoryNotFound':
        return invalid({ categoryId: ['Article category does not exist'] })
      case 'inUse':
        return invalid({
          categoryId: [
            'Article subcategory is used by articles and cannot be moved to another category',
          ],
        })
      default:
        await exampleImages.deleteObsolete(result.obsoleteFilename)
        return ok(result.subcategory)
    }
  }

  return {
    listCategories: async (): Promise<OperationResult<ArticleCategory[]>> =>
      ok(await repository.listCategories()),

    getCategory: async (id: number): Promise<OperationResult<ArticleCategory>> => {
      const category = await repository.findCategory(id)
      return category === undefined ? notFound : ok(category)
    },

    createCategory: async (
      input: ArticleCategoryInput,
    ): Promise<OperationResult<ArticleCategory>> => {
      const stored = await repository.insertCategory(input)
      return stored === 'conflict' ? conflict : ok(stored)
    },

    updateCategory: async (
      id: number,
      input: ArticleCategoryInput,
    ): Promise<OperationResult<ArticleCategory>> => {
      const stored = await repository.updateCategory(id, input)
      if (stored === 'conflict') return conflict
      return stored === 'notFound' ? notFound : ok(stored)
    },

    /** `conflict` means subcategories or articles still use the category. */
    deleteCategory: async (id: number): Promise<OperationResult<undefined>> => {
      const deleted = await repository.deleteCategory(id)
      if (deleted === 'inUse') return conflict
      return deleted === 'notFound' ? notFound : ok(undefined)
    },

    /** The complete new order; `conflict` is the retryable lost race for a position. */
    reorderCategories: async (
      sourceId: number,
      targetId: number,
    ): Promise<OperationResult<ArticleCategory[]>> => {
      const result = await repository.reorderCategories(sourceId, targetId)
      if (result === 'notFound') return notFound
      return result === 'conflict' ? conflict : ok(result)
    },

    listSubcategories: async (): Promise<OperationResult<ArticleSubcategory[]>> =>
      ok(await repository.listSubcategories()),

    getSubcategory: async (id: number): Promise<OperationResult<ArticleSubcategory>> => {
      const subcategory = await repository.findSubcategory(id)
      return subcategory === undefined ? notFound : ok(subcategory)
    },

    createSubcategory: (input: ArticleSubcategoryInput) =>
      writeSubcategory(input, () => repository.insertSubcategory(input)),

    updateSubcategory: (id: number, input: ArticleSubcategoryInput) =>
      writeSubcategory(input, () => repository.updateSubcategory(id, input)),

    /** `conflict` means articles still use the subcategory. */
    deleteSubcategory: async (id: number): Promise<OperationResult<undefined>> => {
      const deleted = await repository.deleteSubcategory(id)
      if (deleted === 'inUse') return conflict
      if (deleted === 'notFound') return notFound
      await exampleImages.deleteObsolete(deleted.obsoleteFilename)
      return ok(undefined)
    },

    reorderSubcategories: async (
      sourceId: number,
      targetId: number,
    ): Promise<OperationResult<ArticleSubcategory[]>> => {
      const result = await repository.reorderSubcategories(sourceId, targetId)
      if (result === 'notFound') return notFound
      return result === 'conflict' ? conflict : ok(result)
    },

    storeExampleImage: exampleImages.store,
  }
}
