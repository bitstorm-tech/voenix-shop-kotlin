import type { AdminArticleListItemDto } from '@/stores/admin/articles'
import type { AdminSpodArticleListItemDto } from '@/stores/admin/spodArticles'

export function createAdminArticleListItem(
  overrides: Partial<AdminArticleListItemDto> = {},
): AdminArticleListItemDto {
  return {
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

/** A SPOD article list row: an article row plus the two columns its second owner fills. */
export function createAdminSpodArticleListItem(
  overrides: Partial<AdminSpodArticleListItemDto> = {},
): AdminSpodArticleListItemDto {
  return {
    ...createAdminArticleListItem({ name: 'Classic tee' }),
    syncedAt: '2026-08-20T08:30:00Z',
    missingAtSpreadconnect: false,
    ...overrides,
  }
}
