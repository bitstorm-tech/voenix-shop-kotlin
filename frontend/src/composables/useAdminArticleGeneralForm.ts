import { computed } from 'vue'
import { useAdminArticleSubcategoriesStore } from '@/stores/admin/articleSubcategories'

/** The value a select shows while nothing is picked; a select cannot hold `null`. */
export const NONE_VALUE = 'none'

interface ArticleGeneralReferences {
  categoryId: number | null
  subcategoryId: number | null
  supplierId: number | null
}

/**
 * The three references every article editor picks in its general tab, as select values.
 *
 * A `<Select>` speaks strings and has no empty value of its own, so each id is bound through a
 * writable computed that folds `none` onto `null`. Picking another category drops a subcategory
 * that does not belong to it, which is what the backend would refuse anyway.
 */
export function useAdminArticleGeneralForm(general: ArticleGeneralReferences) {
  const subcategoriesStore = useAdminArticleSubcategoriesStore()

  const filteredSubcategories = computed(() =>
    subcategoriesStore.subcategories.filter(
      (subcategory) => subcategory.categoryId === general.categoryId,
    ),
  )

  const categorySelectValue = computed({
    get: () => general.categoryId?.toString() ?? NONE_VALUE,
    set: (value: string) => {
      general.categoryId = value === NONE_VALUE ? null : Number(value)
      if (
        general.subcategoryId !== null &&
        !filteredSubcategories.value.some((subcategory) => subcategory.id === general.subcategoryId)
      ) {
        general.subcategoryId = null
      }
    },
  })

  const subcategorySelectValue = computed({
    get: () => general.subcategoryId?.toString() ?? NONE_VALUE,
    set: (value: string) => {
      general.subcategoryId = value === NONE_VALUE ? null : Number(value)
    },
  })

  const supplierSelectValue = computed({
    get: () => general.supplierId?.toString() ?? NONE_VALUE,
    set: (value: string) => {
      general.supplierId = value === NONE_VALUE ? null : Number(value)
    },
  })

  return {
    filteredSubcategories,
    categorySelectValue,
    subcategorySelectValue,
    supplierSelectValue,
  }
}
