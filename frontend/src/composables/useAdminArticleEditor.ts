import { computed, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useToast } from '@/composables/useToast'
import type { useAdminPriceForm } from '@/composables/useAdminPriceForm'
import { useAdminArticleCategoriesStore } from '@/stores/admin/articleCategories'
import { useAdminArticleSubcategoriesStore } from '@/stores/admin/articleSubcategories'
import {
  type AdminArticleDtoByType,
  type AdminArticleType,
  ArticleNotFoundError,
  InvalidArticleRequestError,
  type SaveAdminArticleRequestByType,
  useAdminArticlesStore,
} from '@/stores/admin/articles'
import { useAdminSuppliersStore } from '@/stores/admin/suppliers'
import { useAdminVatStore } from '@/stores/admin/vat'
import type { PriceVatDto } from '@/stores/admin/prices'

type ArticlePriceForm = ReturnType<typeof useAdminPriceForm>

export interface AdminArticleEditorOptions<T extends AdminArticleType> {
  articleType: T
  /** The price tab of this editor, opened whenever the price is what stops the save. */
  priceTab: string
  articlePrice: ArticlePriceForm
  /** Empties the form, including whatever state only this editor has. */
  resetForm: () => void
  fillForm: (article: AdminArticleDtoByType[T]) => void
  clearErrors: () => void
  /** The client-side rules of this type. It opens the tab of the first problem itself. */
  validate: () => boolean
  buildPayload: () => SaveAdminArticleRequestByType[T]
  /** Files a rejected write onto the form; answers the message that belongs next to the form. */
  applySaveErrors: (error: InvalidArticleRequestError) => string | null
  /** Puts "an active article requires a price" on the editor's own price field. */
  showPriceRequired: () => void
  /** The name of the edited article, for the toasts that name it. */
  articleName: () => string
}

/**
 * The lifecycle both article editors share: load the article of the route, save it, delete it.
 *
 * What differs between a mug and a shirt is the form itself — its fields, its rules, its payload —
 * and that is passed in. What does not differ is everything around it: the route id, create versus
 * update, the stale-load guard, a `404` that sends the user back to the list, the price gate before
 * a save, and the toasts. The editors are separate views on purpose (they show different things);
 * this is the part that would otherwise be written twice.
 */
export function useAdminArticleEditor<T extends AdminArticleType>(
  options: AdminArticleEditorOptions<T>,
) {
  const route = useRoute()
  const router = useRouter()
  const articlesStore = useAdminArticlesStore()
  const categoriesStore = useAdminArticleCategoriesStore()
  const subcategoriesStore = useAdminArticleSubcategoriesStore()
  const suppliersStore = useAdminSuppliersStore()
  const vatStore = useAdminVatStore()
  const { toast } = useToast()
  const { articlePrice } = options

  const generalError = shallowRef<string | null>(null)
  const activeTab = shallowRef<string>('general')
  const isLoading = shallowRef(false)
  const isSaving = shallowRef(false)
  const isDeleting = shallowRef(false)
  const isDeleteDialogOpen = shallowRef(false)
  /** Answers which load is the current one, so a slow answer of an abandoned route is dropped. */
  let loadSequence = 0

  const editId = computed(() => getArticleId())
  const isEditMode = computed(() => editId.value !== null)

  /** The VAT rates the price editor offers, plus the two the saved price already uses. */
  const priceVatOptions = computed<PriceVatDto[]>(() => {
    const byId = new Map<number, PriceVatDto>()

    for (const vat of vatStore.vats) {
      byId.set(vat.id, { id: vat.id, name: vat.name, percent: vat.percent })
    }

    const currentPrice = articlePrice.lastCalculatedPrice.value
    if (currentPrice) {
      byId.set(currentPrice.purchaseVat.id, currentPrice.purchaseVat)
      byId.set(currentPrice.salesVat.id, currentPrice.salesVat)
    }

    return [...byId.values()].sort((left, right) => left.id - right.id)
  })

  function getRouteIdParam() {
    return Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
  }

  function getArticleId() {
    const rawId = getRouteIdParam()
    if (rawId === undefined) {
      return null
    }

    const parsedId = Number(rawId)
    return Number.isInteger(parsedId) && parsedId > 0 ? parsedId : null
  }

  function notFoundToast(message?: string) {
    toast({
      title: 'Article not found',
      description: message || 'The requested article does not exist.',
      variant: 'destructive',
    })
  }

  function goToList() {
    return router.push({ name: 'admin-articles', query: route.query })
  }

  function replaceWithList() {
    return router.replace({ name: 'admin-articles', query: route.query })
  }

  async function loadArticleForCurrentRoute() {
    const currentLoad = ++loadSequence

    options.resetForm()
    options.clearErrors()
    generalError.value = null
    activeTab.value = 'general'
    isDeleteDialogOpen.value = false
    isLoading.value = false
    void categoriesStore.fetchCategories()
    void subcategoriesStore.fetchSubcategories()
    void suppliersStore.fetchSuppliers()
    void vatStore.fetchAll()

    if (getRouteIdParam() === undefined) {
      void articlePrice.initialize(null)
      return
    }

    const articleId = editId.value
    if (articleId === null) {
      notFoundToast()
      await replaceWithList()
      return
    }

    isLoading.value = true

    try {
      const article = await articlesStore.fetchArticle(options.articleType, articleId)
      if (currentLoad !== loadSequence) {
        return
      }

      options.fillForm(article)
      await articlePrice.initialize(article.price)
    } catch (error) {
      if (currentLoad !== loadSequence) {
        return
      }

      if (error instanceof ArticleNotFoundError) {
        notFoundToast(error.message)
        await replaceWithList()
        return
      }

      generalError.value = error instanceof Error ? error.message : 'Failed to load article.'
      toast({
        title: 'Failed to load article',
        description: generalError.value,
        variant: 'destructive',
      })
    } finally {
      if (currentLoad === loadSequence) {
        isLoading.value = false
      }
    }
  }

  async function saveArticle() {
    if (isSaving.value) {
      return
    }

    generalError.value = null

    if (!options.validate()) {
      return
    }

    if (articlePrice.isCalculationPending.value && articlePrice.error.value === null) {
      await articlePrice.calculateNow()
    }

    if (!articlePrice.validateForSave()) {
      activeTab.value = options.priceTab
      return
    }

    const payload = options.buildPayload()

    // An active article needs a price row, and the write refuses it with `price: An active article
    // requires a price` otherwise. An untouched price form sends nothing, so the rule is checked
    // here.
    if (payload.active && payload.price === undefined && !articlePrice.hasExistingPrice.value) {
      options.showPriceRequired()
      activeTab.value = options.priceTab
      return
    }

    isSaving.value = true

    try {
      const articleId = editId.value
      const article =
        articleId === null
          ? await articlesStore.createArticle(options.articleType, payload)
          : await articlesStore.updateArticle(options.articleType, articleId, payload)

      toast({
        title: isEditMode.value ? 'Article saved' : 'Article created',
        description: `${article.name} was saved.`,
        variant: 'success',
      })
      await goToList()
    } catch (error) {
      if (error instanceof ArticleNotFoundError) {
        notFoundToast(error.message)
        await replaceWithList()
        return
      }

      const message = error instanceof Error ? error.message : 'Failed to save article.'
      generalError.value =
        error instanceof InvalidArticleRequestError ? options.applySaveErrors(error) : message
      toast({
        title: 'Failed to save article',
        description: generalError.value ?? message,
        variant: 'destructive',
      })
    } finally {
      isSaving.value = false
    }
  }

  async function deleteCurrentArticle() {
    if (isDeleting.value) {
      return
    }

    const articleId = editId.value
    if (articleId === null) {
      return
    }

    isDeleting.value = true
    generalError.value = null

    try {
      await articlesStore.deleteArticle(options.articleType, articleId)
      isDeleteDialogOpen.value = false
      toast({
        title: 'Article deleted',
        description: `${options.articleName() || 'Article'} was deleted.`,
        variant: 'success',
      })
      await goToList()
    } catch (error) {
      if (error instanceof ArticleNotFoundError) {
        isDeleteDialogOpen.value = false
        notFoundToast(error.message)
        await replaceWithList()
        return
      }

      generalError.value = error instanceof Error ? error.message : 'Failed to delete article.'
      toast({
        title: 'Failed to delete article',
        description: generalError.value,
        variant: 'destructive',
      })
    } finally {
      isDeleting.value = false
    }
  }

  watch(
    () => [route.name, getRouteIdParam()] as const,
    () => {
      void loadArticleForCurrentRoute()
    },
    { immediate: true },
  )

  return {
    route,
    editId,
    isEditMode,
    activeTab,
    generalError,
    isLoading,
    isSaving,
    isDeleting,
    isDeleteDialogOpen,
    priceVatOptions,
    categoriesStore,
    subcategoriesStore,
    suppliersStore,
    saveArticle,
    deleteCurrentArticle,
  }
}
