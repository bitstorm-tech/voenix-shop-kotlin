import type { AdminArticleListItem } from '@/stores/admin/articles'

export function createAdminArticleListItem(
  overrides: Partial<AdminArticleListItem> = {},
): AdminArticleListItem {
  return {
    articleType: 'MUG',
    id: 1,
    position: 1,
    name: 'Classic Mug',
    active: true,
    categoryId: null,
    categoryName: null,
    subcategoryId: null,
    subcategoryName: null,
    supplierId: null,
    supplierName: null,
    variantCount: 1,
    exampleImageFilename: null,
    ...overrides,
  }
}
