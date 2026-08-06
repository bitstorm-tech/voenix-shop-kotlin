import { computed, onBeforeUnmount, onMounted, reactive, readonly, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useAdminPriceForm } from '@/composables/useAdminPriceForm'
import { useToast } from '@/composables/useToast'
import { useAdminPromptCategoriesStore } from '@/stores/admin/promptCategories'
import { useAdminPromptsStore } from '@/stores/admin/prompts'
import {
  PromptCreateConflictError,
  PromptNotFoundError,
  PromptSaveError,
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

export interface AdminPromptFieldErrors {
  title?: string
  promptText?: string
  categoryId?: string
  subcategoryId?: string
}

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
      (subcategory) => subcategory.promptCategory.id === form.categoryId,
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

  // Mirrors the backend composition in PromptService.GetPromptTextAsync.
  const fullPromptText = computed(() => {
    const selectedIds = new Set(form.slotVariantIds)
    const slotPrompts = slotsStore.slotVariants
      .filter((variant) => selectedIds.has(variant.id) && variant.prompt.trim() !== '')
      .slice()
      .sort(
        (left, right) =>
          left.slotType.position - right.slotType.position ||
          left.slotType.id - right.slotType.id ||
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

  const taxonomyError = computed(() => categoriesStore.error)
  const slotReferenceError = computed(() => slotsStore.error)
  const vatReferenceError = computed(() => vatStore.error)
  const hasReferenceError = computed(() =>
    Boolean(taxonomyError.value || slotReferenceError.value || vatReferenceError.value),
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
    form.exampleImageFilename = prompt.exampleImageFilename ?? null
    form.categoryId = prompt.category.id
    form.subcategoryId = prompt.subcategory?.id ?? null
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
        slotsStore.fetchSlotTypes(),
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

  function setExampleImageFilename(value: string | null) {
    form.exampleImageFilename = value
    imageSelectionDirty.value = true
    clearSaveError()
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

  function setSlotVariantIds(value: number[]) {
    form.slotVariantIds = [...value]
    clearSaveError()
  }

  function clearFieldErrors() {
    fieldErrors.title = undefined
    fieldErrors.promptText = undefined
    fieldErrors.categoryId = undefined
    fieldErrors.subcategoryId = undefined
  }

  function validatePrompt() {
    clearFieldErrors()
    let valid = true

    if (form.title.trim() === '') {
      fieldErrors.title = t('admin.prompts.editor.validation.title')
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
      const payload = {
        title: form.title,
        promptText: form.promptText,
        llm: form.llm.trim() === '' ? null : form.llm.trim(),
        exampleImageFilename: form.exampleImageFilename,
        categoryId: form.categoryId,
        subcategoryId: form.subcategoryId,
        active: form.active,
        archived: form.archived,
        slotVariantIds: [...form.slotVariantIds],
        price: pricePayload,
      }

      if (promptId === null) {
        await promptsStore.createPrompt(payload)
        await promptsStore.refreshPrompts()
      } else {
        await promptsStore.updatePrompt(promptId, payload)
      }
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
        error instanceof PromptCreateConflictError
          ? t('admin.prompts.editor.errors.createConflict')
          : error instanceof Error
            ? error.message
            : t('admin.prompts.editor.errors.save')
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

  async function retryTaxonomy() {
    await Promise.all([categoriesStore.fetchCategories(), categoriesStore.fetchSubcategories()])
  }

  async function retrySlotReferences() {
    await Promise.all([slotsStore.fetchSlotTypes(), slotsStore.fetchSlotVariants()])
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
    taxonomyError,
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
    retryTaxonomy,
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
