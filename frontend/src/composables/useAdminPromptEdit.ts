import { computed, onBeforeUnmount, onMounted, reactive, readonly, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useAdminPriceForm } from '@/composables/useAdminPriceForm'
import { useToast } from '@/composables/useToast'
import { useAdminPromptCategoriesStore } from '@/stores/admin/promptCategories'
import { useAdminPromptsStore } from '@/stores/admin/prompts'
import {
  PromptNotFoundError,
  PromptSaveError,
  type SaveAdminPromptRequest,
} from '@/stores/admin/prompts'
import { useAdminPromptSlotsStore } from '@/stores/admin/promptSlots'
import type { PriceVatDto } from '@/stores/admin/prices'
import { useAdminVatStore } from '@/stores/admin/vat'

export interface AdminPromptFormState {
  title: string
  promptText: string
  llm: string
  exampleImageFilename: string | null
  categoryId: number | null
  subcategoryId: number | null
  active: boolean
  archived: boolean
  slotVariantIds: readonly number[]
}

/**
 * The messages the editor shows next to its inputs, keyed by the field they belong to.
 *
 * The keys are the JSON paths of the request body, because that is how a refused write reports its
 * problems — `categoryId`, `slotVariantIds`, `exampleImageFilename`. Everything under `price` is
 * the price editor's business and never lands here.
 */
export interface AdminPromptFieldErrors {
  title?: string
  promptText?: string
  categoryId?: string
  subcategoryId?: string
  slotVariantIds?: string
  exampleImageFilename?: string
}

/** The backend's title limit; the editor refuses a longer title before it sends one. */
export const PROMPT_TITLE_MAX_LENGTH = 255

const PROMPT_FIELD_ERROR_KEYS = [
  'title',
  'promptText',
  'categoryId',
  'subcategoryId',
  'slotVariantIds',
  'exampleImageFilename',
] as const satisfies readonly (keyof AdminPromptFieldErrors)[]

export const PROMPT_EDITOR_TABS = {
  prompt: 'prompt',
  price: 'price',
} as const

export type PromptEditorTab = (typeof PROMPT_EDITOR_TABS)[keyof typeof PROMPT_EDITOR_TABS]

export function useAdminPromptEdit(promptId: number | null) {
  const route = useRoute()
  const router = useRouter()
  const { t } = useI18n()
  const { toast } = useToast()
  const promptsStore = useAdminPromptsStore()
  const categoriesStore = useAdminPromptCategoriesStore()
  const slotsStore = useAdminPromptSlotsStore()
  const vatStore = useAdminVatStore()
  const price = useAdminPriceForm({ persistence: 'required' })
  const isCreate = promptId === null

  const form = reactive<AdminPromptFormState>({
    title: '',
    promptText: '',
    llm: '',
    exampleImageFilename: null,
    categoryId: null,
    subcategoryId: null,
    active: true,
    archived: false,
    slotVariantIds: [],
  })
  const fieldErrors = reactive<AdminPromptFieldErrors>({})
  const activeTab = shallowRef<PromptEditorTab>(PROMPT_EDITOR_TABS.prompt)
  const loadError = shallowRef<string | null>(null)
  const isNotFound = shallowRef(false)
  const saveError = shallowRef<string | null>(null)
  const isLoading = shallowRef(true)
  const isSaving = shallowRef(false)
  const isUploadingImage = shallowRef(false)
  const promptBaseline = shallowRef('')
  const imageSelectionDirty = shallowRef(false)

  function promptSnapshot() {
    return JSON.stringify({
      title: form.title,
      promptText: form.promptText,
      llm: form.llm,
      exampleImageFilename: form.exampleImageFilename,
      categoryId: form.categoryId,
      subcategoryId: form.subcategoryId,
      active: form.active,
      archived: form.archived,
      slotVariantIds: [...form.slotVariantIds].sort((left, right) => left - right),
    })
  }

  promptBaseline.value = promptSnapshot()

  const isPromptDirty = computed(
    () => imageSelectionDirty.value || promptSnapshot() !== promptBaseline.value,
  )
  const isDirty = computed(() => isPromptDirty.value || price.isDirty.value)

  const filteredSubcategories = computed(() =>
    categoriesStore.subcategories.filter(
      (subcategory) => subcategory.categoryId === form.categoryId,
    ),
  )

  const priceVatOptions = computed<PriceVatDto[]>(() => {
    const vats = new Map<number, PriceVatDto>()
    for (const vat of vatStore.vats) {
      vats.set(vat.id, { id: vat.id, name: vat.name, percent: vat.percent })
    }

    const currentPrice = price.lastCalculatedPrice.value
    if (currentPrice) {
      vats.set(currentPrice.purchaseVat.id, currentPrice.purchaseVat)
      vats.set(currentPrice.salesVat.id, currentPrice.salesVat)
    }

    return [...vats.values()].sort((left, right) => left.id - right.id)
  })

  /**
   * The slot order the composed prompt text follows. A variant carries only its `slotId`, so the
   * position comes from the slot list this composable already loads.
   */
  const slotPositionById = computed(
    () => new Map(slotsStore.slots.map((slotItem) => [slotItem.id, slotItem.position])),
  )

  // Mirrors the backend composition in PromptService.GetPromptTextAsync.
  const fullPromptText = computed(() => {
    const selectedIds = new Set(form.slotVariantIds)
    const positions = slotPositionById.value
    const slotPrompts = slotsStore.slotVariants
      .filter((variant) => selectedIds.has(variant.id) && variant.prompt.trim() !== '')
      .slice()
      .sort(
        (left, right) =>
          (positions.get(left.slotId) ?? Number.MAX_SAFE_INTEGER) -
            (positions.get(right.slotId) ?? Number.MAX_SAFE_INTEGER) ||
          left.slotId - right.slotId ||
          left.name.localeCompare(right.name) ||
          left.id - right.id,
      )
      .map((variant) => variant.prompt.trim())

    return [form.promptText.trim(), ...slotPrompts].filter((part) => part !== '').join('\n\n')
  })

  const canCopyFullPrompt = computed(() => fullPromptText.value !== '')

  async function copyFullPrompt() {
    if (!canCopyFullPrompt.value) {
      return
    }

    try {
      await navigator.clipboard.writeText(fullPromptText.value)
      toast({
        title: t('admin.prompts.editor.copy.success.title'),
        description: t('admin.prompts.editor.copy.success.description'),
        variant: 'success',
      })
    } catch {
      toast({
        title: t('admin.prompts.editor.copy.error.title'),
        description: t('admin.prompts.editor.copy.error.description'),
        variant: 'destructive',
      })
    }
  }

  const categoryReferenceError = computed(() => categoriesStore.error)
  const slotReferenceError = computed(() => slotsStore.error)
  const vatReferenceError = computed(() => vatStore.error)
  const hasReferenceError = computed(() =>
    Boolean(categoryReferenceError.value || slotReferenceError.value || vatReferenceError.value),
  )
  const isSaveBlocked = computed(
    () =>
      isLoading.value ||
      isSaving.value ||
      isUploadingImage.value ||
      price.isLoading.value ||
      price.isCalculating.value ||
      (price.isCalculationPending.value && price.inputError.value === null) ||
      categoriesStore.isLoading ||
      slotsStore.isLoading ||
      vatStore.isLoading ||
      price.setupError.value !== null ||
      hasReferenceError.value,
  )

  function clearSaveError() {
    saveError.value = null
  }

  function clearFieldError(field: keyof AdminPromptFieldErrors) {
    fieldErrors[field] = undefined
    clearSaveError()
  }

  function fillForm(prompt: Awaited<ReturnType<typeof promptsStore.fetchPrompt>>) {
    form.title = prompt.title
    form.promptText = prompt.promptText
    form.llm = prompt.llm ?? ''
    form.exampleImageFilename = prompt.exampleImageFilename
    form.categoryId = prompt.categoryId
    form.subcategoryId = prompt.subcategoryId
    form.active = prompt.active
    form.archived = prompt.archived
    form.slotVariantIds = [...prompt.slotVariantIds]
  }

  async function load() {
    isLoading.value = true
    loadError.value = null
    isNotFound.value = false

    try {
      const [promptResult] = await Promise.allSettled([
        promptId === null ? Promise.resolve(null) : promptsStore.fetchPrompt(promptId),
        categoriesStore.fetchCategories(),
        categoriesStore.fetchSubcategories(),
        slotsStore.fetchSlots(),
        slotsStore.fetchSlotVariants(),
        vatStore.fetchAll(),
      ])

      if (promptResult.status === 'rejected') {
        isNotFound.value = promptResult.reason instanceof PromptNotFoundError
        throw promptResult.reason
      }

      const prompt = promptResult.value
      if (prompt !== null) {
        fillForm(prompt)
      }
      promptBaseline.value = promptSnapshot()
      imageSelectionDirty.value = false
      await price.initialize(prompt?.price ?? null)
    } catch (error) {
      loadError.value =
        error instanceof Error ? error.message : t('admin.prompts.editor.errors.load')
    } finally {
      isLoading.value = false
    }
  }

  function setTitle(value: string) {
    form.title = value
    clearFieldError('title')
  }

  function setPromptText(value: string) {
    form.promptText = value
    clearFieldError('promptText')
  }

  function setLlm(value: string) {
    form.llm = value
    clearSaveError()
  }

  /** `null` removes the image; there is no separate remove call. */
  function setExampleImageFilename(value: string | null) {
    form.exampleImageFilename = value
    imageSelectionDirty.value = true
    clearFieldError('exampleImageFilename')
  }

  function markExampleImageSelectionDirty() {
    imageSelectionDirty.value = true
    clearSaveError()
  }

  function setCategoryId(value: number | null) {
    form.categoryId = value
    if (
      form.subcategoryId !== null &&
      !filteredSubcategories.value.some((subcategory) => subcategory.id === form.subcategoryId)
    ) {
      form.subcategoryId = null
    }
    clearFieldError('categoryId')
    clearFieldError('subcategoryId')
  }

  function setSubcategoryId(value: number | null) {
    form.subcategoryId = value
    clearFieldError('subcategoryId')
  }

  function setActive(value: boolean) {
    form.active = value
    clearSaveError()
  }

  function setArchived(value: boolean) {
    form.archived = value
    clearSaveError()
  }

  /**
   * The backend deduplicates a repeated variant rather than rejecting it, so a repeated selection
   * is stored as-is and never warned about.
   */
  function setSlotVariantIds(value: number[]) {
    form.slotVariantIds = [...value]
    clearFieldError('slotVariantIds')
  }

  function clearFieldErrors() {
    for (const field of PROMPT_FIELD_ERROR_KEYS) {
      fieldErrors[field] = undefined
    }
  }

  function validatePrompt() {
    clearFieldErrors()
    let valid = true

    if (form.title.trim() === '') {
      fieldErrors.title = t('admin.prompts.editor.validation.title')
      valid = false
    } else if (form.title.trim().length > PROMPT_TITLE_MAX_LENGTH) {
      fieldErrors.title = t('admin.prompts.editor.validation.titleLength', {
        max: PROMPT_TITLE_MAX_LENGTH,
      })
      valid = false
    }
    if (form.promptText.trim() === '') {
      fieldErrors.promptText = t('admin.prompts.editor.validation.promptText')
      valid = false
    }
    if (form.categoryId === null) {
      fieldErrors.categoryId = t('admin.prompts.editor.validation.category')
      valid = false
    }
    if (
      form.subcategoryId !== null &&
      !filteredSubcategories.value.some((subcategory) => subcategory.id === form.subcategoryId)
    ) {
      fieldErrors.subcategoryId = t('admin.prompts.editor.validation.subcategory')
      valid = false
    }

    if (!valid) {
      activeTab.value = PROMPT_EDITOR_TABS.prompt
    }
    return valid
  }

  /**
   * Shows what the backend blamed on a field next to that field. A path the editor has no input for
   * — `price.salesVatId`, say — stays in the summary message the price tab already carries.
   */
  function applySaveFieldErrors(error: PromptSaveError) {
    for (const field of PROMPT_FIELD_ERROR_KEYS) {
      fieldErrors[field] = error.fieldError(field) ?? undefined
    }
  }

  async function save() {
    if (isSaveBlocked.value) {
      if (price.setupError.value !== null) {
        activeTab.value = PROMPT_EDITOR_TABS.price
      }
      return
    }

    saveError.value = null
    if (!validatePrompt()) {
      return
    }

    if (!price.validateForSave()) {
      activeTab.value = PROMPT_EDITOR_TABS.price
      return
    }

    const pricePayload = price.getSavePayload()
    if (form.categoryId === null || pricePayload === undefined) {
      activeTab.value = PROMPT_EDITOR_TABS.price
      return
    }

    isSaving.value = true
    try {
      // `title` and `llm` are stored trimmed, `promptText` verbatim, and a repeated slot variant is
      // deduplicated rather than rejected — sending exactly that keeps the answer free of surprises.
      const payload: SaveAdminPromptRequest = {
        title: form.title.trim(),
        promptText: form.promptText,
        llm: form.llm.trim() === '' ? null : form.llm.trim(),
        exampleImageFilename: form.exampleImageFilename,
        categoryId: form.categoryId,
        subcategoryId: form.subcategoryId,
        active: form.active,
        archived: form.archived,
        slotVariantIds: [...new Set(form.slotVariantIds)],
        price: pricePayload,
      }

      if (promptId === null) {
        await promptsStore.createPrompt(payload)
      } else {
        await promptsStore.updatePrompt(promptId, payload)
      }
      // The written prompt cannot patch its own list row — the row shows the category names, the
      // detail carries the ids — so the list is loaded again instead.
      await promptsStore.refreshPrompts()
      toast({
        title: t(
          isCreate
            ? 'admin.prompts.editor.createSuccess.title'
            : 'admin.prompts.editor.success.title',
        ),
        description: t(
          isCreate
            ? 'admin.prompts.editor.createSuccess.description'
            : 'admin.prompts.editor.success.description',
        ),
        variant: 'success',
      })
      clearDirtyProtection()
      await router.push({ name: 'admin-prompts', query: route.query })
    } catch (error) {
      saveError.value =
        error instanceof Error ? error.message : t('admin.prompts.editor.errors.save')
      if (error instanceof PromptSaveError) {
        applySaveFieldErrors(error)
      }
      activeTab.value =
        error instanceof PromptSaveError && error.section === 'price'
          ? PROMPT_EDITOR_TABS.price
          : PROMPT_EDITOR_TABS.prompt
    } finally {
      isSaving.value = false
    }
  }

  function cancel() {
    return router.push({ name: 'admin-prompts', query: route.query })
  }

  function clearDirtyProtection() {
    promptBaseline.value = promptSnapshot()
    imageSelectionDirty.value = false
    price.markClean()
  }

  async function retryCategoryReferences() {
    await Promise.all([categoriesStore.fetchCategories(), categoriesStore.fetchSubcategories()])
  }

  async function retrySlotReferences() {
    await Promise.all([slotsStore.fetchSlots(), slotsStore.fetchSlotVariants()])
  }

  async function retryVatReferences() {
    await vatStore.fetchAll()
  }

  async function retryPriceInitialization() {
    await price.initialize(null)
  }

  function onBeforeUnload(event: BeforeUnloadEvent) {
    if (!isDirty.value) {
      return
    }

    event.preventDefault()
    event.returnValue = ''
  }

  watch(
    isDirty,
    (dirty) => {
      if (dirty) {
        window.addEventListener('beforeunload', onBeforeUnload)
      } else {
        window.removeEventListener('beforeunload', onBeforeUnload)
      }
    },
    { flush: 'sync' },
  )

  watch(
    () => price.error.value,
    (error) => {
      if (error) {
        activeTab.value = PROMPT_EDITOR_TABS.price
      }
    },
  )

  function confirmDirtyNavigation() {
    if (!isDirty.value) {
      return true
    }

    return window.confirm(t('admin.prompts.editor.unsaved.confirm'))
  }

  onBeforeRouteLeave(confirmDirtyNavigation)
  onBeforeRouteUpdate(confirmDirtyNavigation)

  onMounted(load)
  onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))

  return {
    form: readonly(form),
    isCreate,
    fieldErrors: readonly(fieldErrors),
    activeTab,
    loadError: readonly(loadError),
    isNotFound: readonly(isNotFound),
    saveError: readonly(saveError),
    isLoading: readonly(isLoading),
    isSaving: readonly(isSaving),
    isUploadingImage: readonly(isUploadingImage),
    categoriesStore,
    slotsStore,
    vatStore,
    price,
    priceVatOptions,
    categoryReferenceError,
    slotReferenceError,
    vatReferenceError,
    hasReferenceError,
    isDirty,
    isSaveBlocked,
    fullPromptText,
    canCopyFullPrompt,
    copyFullPrompt,
    save,
    cancel,
    reload: load,
    retryCategoryReferences,
    retrySlotReferences,
    retryVatReferences,
    retryPriceInitialization,
    uploadExampleImage: promptsStore.uploadExampleImage,
    setUploadingImage: (value: boolean) => {
      isUploadingImage.value = value
    },
    setTitle,
    setPromptText,
    setLlm,
    markExampleImageSelectionDirty,
    setExampleImageFilename,
    setCategoryId,
    setSubcategoryId,
    setActive,
    setArchived,
    setSlotVariantIds,
  }
}

export type AdminPromptEditController = ReturnType<typeof useAdminPromptEdit>
