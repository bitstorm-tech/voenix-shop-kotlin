import type { ExampleImages } from '@/modules/image'
import { ok, type OperationResult } from '@/platform/operation'
import type { ArticleSubcategory, CategoryRepository, SubcategoryWrite } from './repository'

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
 * The category structure. The category half is the repository's; the subcategory half also owns
 * the example-image rule: a submitted name is checked before the write, and a name no row refers
 * to any more is deleted after the commit.
 */
export function createCategoryService(
  repository: CategoryRepository,
  exampleImages: ExampleImages,
) {
  /** Checks the submitted image, then writes, then removes the file the write orphaned. */
  const writeSubcategory = async (
    input: ArticleSubcategoryInput,
    write: () => Promise<OperationResult<SubcategoryWrite>>,
  ): Promise<OperationResult<ArticleSubcategory>> => {
    const checked = await exampleImages.checkSubmitted(
      'exampleImageFilename',
      input.exampleImageFilename,
    )
    if (checked.kind !== 'ok') return checked
    const result = await write()
    if (result.kind !== 'ok') return result
    await exampleImages.deleteObsolete(result.value.obsoleteFilename)
    return ok(result.value.subcategory)
  }

  return {
    listCategories: repository.listCategories,
    getCategory: repository.getCategory,
    createCategory: repository.createCategory,
    updateCategory: repository.updateCategory,
    /** `conflict` means subcategories or articles still use the category. */
    deleteCategory: repository.deleteCategory,
    /** The complete new order; `conflict` is the retryable lost race for a position. */
    reorderCategories: repository.reorderCategories,
    listSubcategories: repository.listSubcategories,
    getSubcategory: repository.getSubcategory,

    createSubcategory: (input: ArticleSubcategoryInput) =>
      writeSubcategory(input, () => repository.insertSubcategory(input)),

    updateSubcategory: (id: number, input: ArticleSubcategoryInput) =>
      writeSubcategory(input, () => repository.updateSubcategory(id, input)),

    /** `conflict` means articles still use the subcategory. */
    deleteSubcategory: async (id: number): Promise<OperationResult<undefined>> => {
      const deleted = await repository.deleteSubcategory(id)
      if (deleted.kind !== 'ok') return deleted
      await exampleImages.deleteObsolete(deleted.value)
      return ok(undefined)
    },

    reorderSubcategories: repository.reorderSubcategories,

    storeExampleImage: exampleImages.store,
  }
}
