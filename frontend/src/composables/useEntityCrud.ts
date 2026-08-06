import { computed, shallowRef, watch, type Ref } from 'vue'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { useToast } from '@/composables/useToast'

export interface EntityCrudMessage {
  title: string
  fallbackDescription: string
}

export interface EntityCrudMessages {
  notFound: EntityCrudMessage
  loadFailed: EntityCrudMessage
  saveFailed: EntityCrudMessage
  deleteFailed: EntityCrudMessage
}

export interface EntityCrudErrors {
  generalError: Ref<string | null>
  clearErrors: () => void
}

export interface EntityCrudHandlerContext {
  closeDeleteDialog: () => void
}

export interface EntityCrudToast {
  title: string
  description: string
}

export interface UseEntityCrudOptions<TEntity, TPayload> {
  listRoute: RouteLocationRaw
  messages: EntityCrudMessages
  errors: EntityCrudErrors
  notFoundError: new (...args: never[]) => Error
  fetchEntity: (id: number) => Promise<TEntity>
  createEntity: (payload: TPayload) => Promise<TEntity>
  updateEntity: (id: number, payload: TPayload) => Promise<TEntity>
  deleteEntity: (id: number) => Promise<void>
  resetForm: () => void
  fillForm: (entity: TEntity) => void
  validate: () => boolean
  buildPayload: () => TPayload
  savedToast: (entity: TEntity, isEditMode: boolean) => EntityCrudToast
  deletedToast: () => EntityCrudToast
  onLoadStart?: () => void
  handleSaveError?: (
    error: unknown,
    context: EntityCrudHandlerContext,
  ) => boolean | Promise<boolean>
  handleDeleteError?: (
    error: unknown,
    context: EntityCrudHandlerContext,
  ) => boolean | Promise<boolean>
}

/**
 * Route-based editor CRUD. ArticleEditView is the only remaining consumer;
 * new admin CRUD uses the dialog pattern via useDialogCrud instead.
 */
export function useEntityCrud<TEntity, TPayload>(options: UseEntityCrudOptions<TEntity, TPayload>) {
  const route = useRoute()
  const router = useRouter()
  const { toast } = useToast()
  const { messages, errors } = options

  const isLoading = shallowRef(false)
  const isSaving = shallowRef(false)
  const isDeleting = shallowRef(false)
  const isDeleteDialogOpen = shallowRef(false)
  let loadSequence = 0

  const editId = computed(() => getEntityId())
  const isEditMode = computed(() => editId.value !== null)

  const handlerContext: EntityCrudHandlerContext = {
    closeDeleteDialog: () => {
      isDeleteDialogOpen.value = false
    },
  }

  function getRouteIdParam() {
    return Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
  }

  function getEntityId() {
    const rawId = getRouteIdParam()
    if (rawId === undefined) {
      return null
    }

    const parsedId = Number(rawId)
    return Number.isInteger(parsedId) && parsedId > 0 ? parsedId : null
  }

  function notFoundToast(error?: unknown) {
    toast({
      title: messages.notFound.title,
      description:
        (error instanceof Error && error.message) || messages.notFound.fallbackDescription,
      variant: 'destructive',
    })
  }

  function failureToast(message: EntityCrudMessage, error: unknown) {
    errors.generalError.value = error instanceof Error ? error.message : message.fallbackDescription
    toast({
      title: message.title,
      description: errors.generalError.value,
      variant: 'destructive',
    })
  }

  async function loadEntityForCurrentRoute() {
    const currentLoad = ++loadSequence

    options.resetForm()
    errors.clearErrors()
    isDeleteDialogOpen.value = false
    isLoading.value = false
    options.onLoadStart?.()

    if (getRouteIdParam() === undefined) {
      return
    }

    const entityId = editId.value
    if (entityId === null) {
      notFoundToast()
      await router.replace(options.listRoute)
      return
    }

    isLoading.value = true

    try {
      const entity = await options.fetchEntity(entityId)
      if (currentLoad !== loadSequence) {
        return
      }

      options.fillForm(entity)
    } catch (error) {
      if (currentLoad !== loadSequence) {
        return
      }

      if (error instanceof options.notFoundError) {
        notFoundToast(error)
        await router.replace(options.listRoute)
        return
      }

      failureToast(messages.loadFailed, error)
    } finally {
      if (currentLoad === loadSequence) {
        isLoading.value = false
      }
    }
  }

  async function save() {
    if (isSaving.value) {
      return
    }

    if (!options.validate()) {
      return
    }

    isSaving.value = true

    try {
      const payload = options.buildPayload()
      const entityId = editId.value
      const entity =
        entityId === null
          ? await options.createEntity(payload)
          : await options.updateEntity(entityId, payload)

      options.fillForm(entity)
      toast({ ...options.savedToast(entity, isEditMode.value), variant: 'success' })
      await router.push(options.listRoute)
    } catch (error) {
      if (options.handleSaveError && (await options.handleSaveError(error, handlerContext))) {
        return
      }

      if (error instanceof options.notFoundError) {
        notFoundToast(error)
        await router.replace(options.listRoute)
        return
      }

      failureToast(messages.saveFailed, error)
    } finally {
      isSaving.value = false
    }
  }

  async function deleteCurrent() {
    if (isDeleting.value) {
      return
    }

    const entityId = editId.value
    if (entityId === null) {
      return
    }

    isDeleting.value = true
    errors.generalError.value = null

    try {
      await options.deleteEntity(entityId)
      isDeleteDialogOpen.value = false
      toast({ ...options.deletedToast(), variant: 'success' })
      await router.push(options.listRoute)
    } catch (error) {
      if (options.handleDeleteError && (await options.handleDeleteError(error, handlerContext))) {
        return
      }

      if (error instanceof options.notFoundError) {
        isDeleteDialogOpen.value = false
        notFoundToast(error)
        await router.replace(options.listRoute)
        return
      }

      failureToast(messages.deleteFailed, error)
    } finally {
      isDeleting.value = false
    }
  }

  watch(
    () => [route.name, getRouteIdParam()] as const,
    () => {
      void loadEntityForCurrentRoute()
    },
    { immediate: true },
  )

  return {
    editId,
    isEditMode,
    isLoading,
    isSaving,
    isDeleting,
    isDeleteDialogOpen,
    save,
    deleteCurrent,
  }
}
