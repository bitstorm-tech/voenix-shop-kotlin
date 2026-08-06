import type { AdminArticleListItemDto } from '@/stores/admin/articles'

export function createAdminArticleListItem(
  overrides: Partial<AdminArticleListItemDto> = {},
): AdminArticleListItemDto {
  return {
    id: 1,
    position: 1,
    name: 'Classic Mug',
    articleType: 'MUG',
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
