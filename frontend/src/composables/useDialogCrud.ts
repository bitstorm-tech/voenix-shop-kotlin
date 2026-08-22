import { computed, shallowRef, type Ref } from 'vue'
import { useToast } from '@/composables/useToast'

export interface DialogCrudMessage {
  title: string
  fallbackDescription: string
}

export interface DialogCrudMessages {
  notFound?: DialogCrudMessage
  loadFailed?: DialogCrudMessage
  saveFailed: DialogCrudMessage
  deleteFailed?: DialogCrudMessage
}

export interface DialogCrudErrors {
  generalError: Ref<string | null>
  clearErrors: () => void
}

export interface DialogCrudToast {
  title: string
  description: string
}

export interface DialogCrudHandlerContext<TEntity> {
  close: () => boolean
  readonly selected: Readonly<TEntity> | null
  replaceSelected: (entity: TEntity) => boolean
}

export interface UseDialogCrudOptions<TEntity, TPayload> {
  errors: DialogCrudErrors
  notFoundError?: new (...args: never[]) => Error
  messages: DialogCrudMessages
  fetchEntity?: (id: number) => Promise<TEntity>
  createEntity?: (payload: TPayload) => Promise<TEntity>
  updateEntity: (id: number, payload: TPayload) => Promise<TEntity>
  deleteEntity?: (id: number) => Promise<void>
  getId: (entity: TEntity) => number
  savedToast: (entity: TEntity, isEditMode: boolean) => DialogCrudToast
  deletedToast?: (entity: TEntity) => DialogCrudToast
  onNotFound?: () => Promise<unknown> | unknown
  resolveErrorDescription?: (error: unknown, fallbackDescription: string) => string
  handleSaveError?: (
    error: unknown,
    context: DialogCrudHandlerContext<TEntity>,
  ) => boolean | Promise<boolean>
  handleDeleteError?: (
    error: unknown,
    context: DialogCrudHandlerContext<TEntity>,
  ) => boolean | Promise<boolean>
}

/**
 * The CRUD lifecycle of an admin dialog: the entity comes from the already
 * loaded list, success closes the dialog instead of navigating. Entities
 * that need a detail fetch open via openEditById (provide fetchEntity).
 * Create and delete are optional for update-only dialogs.
 */
export function useDialogCrud<TEntity, TPayload>(options: UseDialogCrudOptions<TEntity, TPayload>) {
  const { toast } = useToast()
  const { messages, errors } = options

  const isDialogOpen = shallowRef(false)
  const selected = shallowRef<TEntity | null>(null)
  const editingId = shallowRef<number | null>(null)
  const isLoadingSelected = shallowRef(false)
  const isSaving = shallowRef(false)
  const isDeleting = shallowRef(false)
  let loadSequence = 0

  const isEditMode = computed(() => editingId.value !== null)
  const isOpen = computed({
    get: () => isDialogOpen.value,
    set: (value: boolean) => {
      if (value) {
        isDialogOpen.value = true
        return
      }

      closeDialog()
    },
  })

  function openCreate() {
    loadSequence++
    editingId.value = null
    selected.value = null
    isLoadingSelected.value = false
    errors.clearErrors()
    isDialogOpen.value = true
  }

  function openEdit(entity: TEntity) {
    loadSequence++
    editingId.value = options.getId(entity)
    selected.value = entity
    isLoadingSelected.value = false
    errors.clearErrors()
    isDialogOpen.value = true
  }

  async function openEditById(id: number) {
    if (!options.fetchEntity) {
      return
    }

    const currentLoad = ++loadSequence
    editingId.value = id
    selected.value = null
    errors.clearErrors()
    isLoadingSelected.value = true
    isDialogOpen.value = true

    try {
      const entity = await options.fetchEntity(id)
      if (currentLoad !== loadSequence) {
        return
      }

      selected.value = entity
    } catch (error) {
      if (currentLoad !== loadSequence) {
        return
      }

      if (isNotFound(error)) {
        await handleNotFound(error)
        return
      }

      const message = messages.loadFailed ?? messages.saveFailed
      toast({
        title: message.title,
        description: resolveErrorDescription(error, message),
        variant: 'destructive',
      })
      closeDialog()
    } finally {
      if (currentLoad === loadSequence) {
        isLoadingSelected.value = false
      }
    }
  }

  function closeDialog() {
    loadSequence++
    editingId.value = null
    selected.value = null
    isLoadingSelected.value = false
    isDialogOpen.value = false
  }

  function isCurrentSequence(sequence: number) {
    return sequence === loadSequence
  }

  function createHandlerContext(sequence: number): DialogCrudHandlerContext<TEntity> {
    return {
      close: () => {
        if (!isCurrentSequence(sequence)) {
          return false
        }

        closeDialog()
        return true
      },
      selected: selected.value,
      replaceSelected: (entity) => {
        const currentId = editingId.value
        if (
          !isCurrentSequence(sequence) ||
          currentId === null ||
          options.getId(entity) !== currentId
        ) {
          return false
        }

        selected.value = entity
        return true
      },
    }
  }

  function isNotFound(error: unknown): error is Error {
    return options.notFoundError !== undefined && error instanceof options.notFoundError
  }

  async function handleNotFound(error: Error) {
    const message = messages.notFound ?? messages.loadFailed ?? messages.saveFailed
    toast({
      title: message.title,
      description: resolveErrorDescription(error, message),
      variant: 'destructive',
    })
    closeDialog()
    await options.onNotFound?.()
  }

  function failureToast(message: DialogCrudMessage, error: unknown) {
    errors.generalError.value = resolveErrorDescription(error, message)
    toast({
      title: message.title,
      description: errors.generalError.value,
      variant: 'destructive',
    })
  }

  function resolveErrorDescription(error: unknown, message: DialogCrudMessage) {
    if (options.resolveErrorDescription) {
      return options.resolveErrorDescription(error, message.fallbackDescription)
    }

    return error instanceof Error && error.message ? error.message : message.fallbackDescription
  }

  async function save(payload: TPayload) {
    if (isSaving.value || isDeleting.value || isLoadingSelected.value) {
      return
    }

    const currentId = editingId.value
    const createEntity = options.createEntity
    if (currentId === null && !createEntity) {
      return
    }

    isSaving.value = true
    errors.clearErrors()
    const operationSequence = loadSequence

    try {
      const entity =
        currentId !== null
          ? await options.updateEntity(currentId, payload)
          : await (createEntity as (payload: TPayload) => Promise<TEntity>)(payload)

      toast({ ...options.savedToast(entity, currentId !== null), variant: 'success' })
      if (isCurrentSequence(operationSequence)) {
        closeDialog()
      }
    } catch (error) {
      if (!isCurrentSequence(operationSequence)) {
        return
      }

      if (options.handleSaveError) {
        const handled = await options.handleSaveError(
          error,
          createHandlerContext(operationSequence),
        )
        if (!isCurrentSequence(operationSequence) || handled) {
          return
        }
      }

      if (isNotFound(error)) {
        await handleNotFound(error)
        return
      }

      failureToast(messages.saveFailed, error)
    } finally {
      isSaving.value = false
    }
  }

  async function deleteSelected() {
    const current = selected.value
    const deleteEntity = options.deleteEntity
    if (isDeleting.value || isSaving.value || isLoadingSelected.value) {
      return
    }

    if (!deleteEntity || current === null) {
      return
    }

    isDeleting.value = true
    errors.clearErrors()
    const operationSequence = loadSequence

    try {
      await deleteEntity(options.getId(current))
      if (options.deletedToast) {
        toast({ ...options.deletedToast(current), variant: 'success' })
      }
      if (isCurrentSequence(operationSequence)) {
        closeDialog()
      }
    } catch (error) {
      if (!isCurrentSequence(operationSequence)) {
        return
      }

      if (options.handleDeleteError) {
        const handled = await options.handleDeleteError(
          error,
          createHandlerContext(operationSequence),
        )
        if (!isCurrentSequence(operationSequence) || handled) {
          return
        }
      }

      if (isNotFound(error)) {
        await handleNotFound(error)
        return
      }

      failureToast(messages.deleteFailed ?? messages.saveFailed, error)
    } finally {
      isDeleting.value = false
    }
  }

  return {
    isOpen,
    selected,
    isEditMode,
    isLoadingSelected,
    isSaving,
    isDeleting,
    openCreate,
    openEdit,
    openEditById,
    save,
    deleteSelected,
  }
}
