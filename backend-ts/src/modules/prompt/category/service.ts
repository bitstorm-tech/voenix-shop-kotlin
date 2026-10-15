import { invalid, ok, type OperationResult } from '@/platform/operation'
import { deleted, found, written } from '../outcomes'
import type {
  CategoryValues,
  PromptCategory,
  PromptCategoryRepository,
  PromptSubcategory,
  SubcategoryValues,
} from './repository'

export type PromptCategoryService = ReturnType<typeof createPromptCategoryService>

/**
 * Categories and subcategories. `conflict` is a name that already exists (for a subcategory, in
 * its category), a delete a row still references, or a lost reorder race; the routes word the
 * three apart. Both rejections that concern a subcategory's category are field errors.
 */
export function createPromptCategoryService(repository: PromptCategoryRepository) {
  return {
    listCategories: async (): Promise<OperationResult<PromptCategory[]>> =>
      ok(await repository.listCategories()),

    getCategory: async (id: number): Promise<OperationResult<PromptCategory>> =>
      found(await repository.findCategory(id)),

    createCategory: async (values: CategoryValues): Promise<OperationResult<PromptCategory>> =>
      written(await repository.insertCategory(values)),

    updateCategory: async (
      id: number,
      values: CategoryValues,
    ): Promise<OperationResult<PromptCategory>> =>
      written(await repository.updateCategory(id, values)),

    deleteCategory: async (id: number): Promise<OperationResult<undefined>> =>
      deleted(await repository.deleteCategory(id)),

    reorderCategories: async (
      sourceId: number,
      targetId: number,
    ): Promise<OperationResult<PromptCategory[]>> =>
      written(await repository.reorderCategories(sourceId, targetId)),

    listSubcategories: async (): Promise<OperationResult<PromptSubcategory[]>> =>
      ok(await repository.listSubcategories()),

    getSubcategory: async (id: number): Promise<OperationResult<PromptSubcategory>> =>
      found(await repository.findSubcategory(id)),

    createSubcategory: async (
      values: SubcategoryValues,
    ): Promise<OperationResult<PromptSubcategory>> => {
      const stored = await repository.insertSubcategory(values)
      if (stored === 'categoryNotFound') return categoryError('Prompt category does not exist')
      return written(stored)
    },

    updateSubcategory: async (
      id: number,
      values: SubcategoryValues,
    ): Promise<OperationResult<PromptSubcategory>> => {
      const stored = await repository.updateSubcategory(id, values)
      if (stored === 'categoryNotFound') return categoryError('Prompt category does not exist')
      if (stored === 'inUse') {
        return categoryError(
          'Prompt subcategory is used by prompts and cannot be moved to another category',
        )
      }
      return written(stored)
    },

    deleteSubcategory: async (id: number): Promise<OperationResult<undefined>> =>
      deleted(await repository.deleteSubcategory(id)),

    reorderSubcategories: async (
      sourceId: number,
      targetId: number,
    ): Promise<OperationResult<PromptSubcategory[]>> =>
      written(await repository.reorderSubcategories(sourceId, targetId)),
  }
}

const categoryError = (message: string) => invalid({ categoryId: [message] })
