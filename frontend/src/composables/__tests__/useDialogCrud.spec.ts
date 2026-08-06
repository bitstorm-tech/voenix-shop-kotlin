import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useDialogCrud, type UseDialogCrudOptions } from '../useDialogCrud'
import { useFormErrors } from '../useFormErrors'

const mocks = vi.hoisted(() => ({
  toast: vi.fn(),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

class TestNotFoundError extends Error {}

interface TestEntity {
  id: number
  name: string
}

interface TestPayload {
  name: string
}

type TestOptions = UseDialogCrudOptions<TestEntity, TestPayload>

function deferredPromise<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })

  return { promise, resolve, reject }
}

function createCrud(overrides: Partial<TestOptions> = {}) {
  const errors = useFormErrors<'name'>()
  const fns = {
    fetchEntity: vi.fn(async (id: number): Promise<TestEntity> => ({ id, name: `Entity ${id}` })),
    createEntity: vi.fn(
      async (payload: TestPayload): Promise<TestEntity> => ({ id: 99, ...payload }),
    ),
    updateEntity: vi.fn(
      async (id: number, payload: TestPayload): Promise<TestEntity> => ({ id, ...payload }),
    ),
    deleteEntity: vi.fn(async (): Promise<void> => {}),
    onNotFound: vi.fn(async (): Promise<void> => {}),
  }

  const crud = useDialogCrud<TestEntity, TestPayload>({
    errors: { generalError: errors.generalError, clearErrors: errors.clearErrors },
    notFoundError: TestNotFoundError,
    messages: {
      notFound: {
        title: 'Entity not found',
        fallbackDescription: 'The requested entity does not exist.',
      },
      loadFailed: {
        title: 'Failed to load entity',
        fallbackDescription: 'Failed to load entity.',
      },
      saveFailed: {
        title: 'Failed to save entity',
        fallbackDescription: 'Failed to save entity.',
      },
      deleteFailed: {
        title: 'Failed to delete entity',
        fallbackDescription: 'Failed to delete entity.',
      },
    },
    getId: (entity) => entity.id,
    savedToast: (entity, isEditMode) => ({
      title: isEditMode ? 'Entity saved' : 'Entity created',
      description: `${entity.name} was saved.`,
    }),
    deletedToast: (entity) => ({
      title: 'Entity deleted',
      description: `${entity.name} was deleted.`,
    }),
    ...fns,
    ...overrides,
  })

  return { crud, fns, errors }
}

describe('useDialogCrud', () => {
  beforeEach(() => {
    mocks.toast.mockReset()
  })

  it('creates the entity and closes the dialog on save in create mode', async () => {
    const { crud, fns } = createCrud()

    crud.openCreate()

    expect(crud.isOpen.value).toBe(true)
    expect(crud.isEditMode.value).toBe(false)

    await crud.save({ name: 'Payload' })

    expect(fns.createEntity).toHaveBeenCalledWith({ name: 'Payload' })
    expect(fns.updateEntity).not.toHaveBeenCalled()
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity created',
      description: 'Payload was saved.',
      variant: 'success',
    })
    expect(crud.isOpen.value).toBe(false)
    expect(crud.selected.value).toBeNull()
    expect(crud.isSaving.value).toBe(false)
  })

  it('updates the selected entity and closes the dialog on save in edit mode', async () => {
    const { crud, fns } = createCrud()

    crud.openEdit({ id: 5, name: 'Entity 5' })

    expect(crud.isEditMode.value).toBe(true)

    await crud.save({ name: 'Payload' })

    expect(fns.updateEntity).toHaveBeenCalledWith(5, { name: 'Payload' })
    expect(fns.createEntity).not.toHaveBeenCalled()
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity saved',
      description: 'Payload was saved.',
      variant: 'success',
    })
    expect(crud.isOpen.value).toBe(false)
    expect(crud.selected.value).toBeNull()
  })

  it('clears previous errors when opening the dialog', () => {
    const { crud, errors } = createCrud()
    errors.generalError.value = 'stale error'
    errors.fieldErrors.name = 'stale field error'

    crud.openCreate()

    expect(errors.generalError.value).toBeNull()
    expect(errors.fieldErrors.name).toBeUndefined()
  })

  it('cleans up edit state when the dialog is closed through v-model', () => {
    const { crud } = createCrud()

    crud.openEdit({ id: 5, name: 'Entity 5' })
    crud.isOpen.value = false

    expect(crud.isOpen.value).toBe(false)
    expect(crud.selected.value).toBeNull()
    expect(crud.isEditMode.value).toBe(false)
  })

  it('delegates save conflicts to handleSaveError and keeps the dialog open', async () => {
    const conflict = new Error('duplicate name')
    const handleSaveError = vi.fn((error: unknown) => error === conflict)
    const { crud, fns, errors } = createCrud({
      handleSaveError: (error) => {
        if (handleSaveError(error)) {
          errors.fieldErrors.name = 'Name already exists.'
          return true
        }
        return false
      },
    })
    crud.openCreate()
    fns.createEntity.mockRejectedValueOnce(conflict)

    await crud.save({ name: 'Payload' })

    expect(handleSaveError).toHaveBeenCalledWith(conflict)
    expect(errors.fieldErrors.name).toBe('Name already exists.')
    expect(errors.generalError.value).toBeNull()
    expect(mocks.toast).not.toHaveBeenCalled()
    expect(crud.isOpen.value).toBe(true)
    expect(crud.isSaving.value).toBe(false)
  })

  it('lets an error handler replace only the selection owned by its operation', async () => {
    const conflict = new Error('conflict')
    const refreshedEntity = deferredPromise<TestEntity>()
    const { crud, fns } = createCrud({
      handleSaveError: async (_error, context) => {
        expect(context.selected).toEqual({ id: 5, name: 'Entity 5' })
        expect(context.replaceSelected({ id: 5, name: 'Locked entity' })).toBe(true)

        const refreshed = await refreshedEntity.promise
        expect(context.replaceSelected(refreshed)).toBe(false)
        expect(context.close()).toBe(false)
        return true
      },
    })
    fns.updateEntity.mockRejectedValueOnce(conflict)
    crud.openEdit({ id: 5, name: 'Entity 5' })

    const saving = crud.save({ name: 'Payload' })
    await vi.waitFor(() => expect(crud.selected.value).toEqual({ id: 5, name: 'Locked entity' }))

    crud.isOpen.value = false
    crud.openEdit({ id: 9, name: 'Entity 9' })
    refreshedEntity.resolve({ id: 5, name: 'Refreshed entity' })
    await saving

    expect(crud.selected.value).toEqual({ id: 9, name: 'Entity 9' })
  })

  it('keeps a newly opened dialog open when an older save succeeds', async () => {
    const { crud, fns } = createCrud()
    const savedEntity = deferredPromise<TestEntity>()
    fns.updateEntity.mockReturnValueOnce(savedEntity.promise)

    crud.openEdit({ id: 5, name: 'Entity 5' })
    const saving = crud.save({ name: 'Older payload' })

    crud.isOpen.value = false
    crud.openEdit({ id: 9, name: 'Entity 9' })
    savedEntity.resolve({ id: 5, name: 'Older payload' })

    await saving

    expect(fns.updateEntity).toHaveBeenCalledWith(5, { name: 'Older payload' })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity saved',
      description: 'Older payload was saved.',
      variant: 'success',
    })
    expect(crud.isOpen.value).toBe(true)
    expect(crud.selected.value).toEqual({ id: 9, name: 'Entity 9' })
    expect(crud.isSaving.value).toBe(false)
  })

  it('ignores stale save errors after another dialog has opened', async () => {
    const { crud, fns, errors } = createCrud()
    const failedSave = deferredPromise<TestEntity>()
    fns.updateEntity.mockReturnValueOnce(failedSave.promise)

    crud.openEdit({ id: 5, name: 'Entity 5' })
    const saving = crud.save({ name: 'Older payload' })

    crud.isOpen.value = false
    crud.openEdit({ id: 9, name: 'Entity 9' })
    failedSave.reject(new Error('old failure'))

    await saving

    expect(errors.generalError.value).toBeNull()
    expect(mocks.toast).not.toHaveBeenCalled()
    expect(crud.isOpen.value).toBe(true)
    expect(crud.selected.value).toEqual({ id: 9, name: 'Entity 9' })
    expect(crud.isSaving.value).toBe(false)
  })

  it('closes the dialog with a toast and reloads when saving hits a not-found error', async () => {
    const { crud, fns } = createCrud()
    crud.openEdit({ id: 5, name: 'Entity 5' })
    fns.updateEntity.mockRejectedValueOnce(new TestNotFoundError('Entity gone'))

    await crud.save({ name: 'Payload' })

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity not found',
      description: 'Entity gone',
      variant: 'destructive',
    })
    expect(crud.isOpen.value).toBe(false)
    expect(crud.selected.value).toBeNull()
    expect(fns.onNotFound).toHaveBeenCalled()
  })

  it('sets the general error and keeps the dialog open on unexpected save failures', async () => {
    const { crud, fns, errors } = createCrud()
    crud.openCreate()
    fns.createEntity.mockRejectedValueOnce(new Error('boom'))

    await crud.save({ name: 'Payload' })

    expect(errors.generalError.value).toBe('boom')
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Failed to save entity',
      description: 'boom',
      variant: 'destructive',
    })
    expect(crud.isOpen.value).toBe(true)
  })

  it('does nothing when deleting without a selected entity', async () => {
    const { crud, fns } = createCrud()
    crud.openCreate()

    await crud.deleteSelected()

    expect(fns.deleteEntity).not.toHaveBeenCalled()
    expect(mocks.toast).not.toHaveBeenCalled()
  })

  it('deletes the selected entity and closes the dialog', async () => {
    const { crud, fns } = createCrud()
    crud.openEdit({ id: 5, name: 'Entity 5' })

    await crud.deleteSelected()

    expect(fns.deleteEntity).toHaveBeenCalledWith(5)
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity deleted',
      description: 'Entity 5 was deleted.',
      variant: 'success',
    })
    expect(crud.isOpen.value).toBe(false)
    expect(crud.selected.value).toBeNull()
    expect(crud.isDeleting.value).toBe(false)
  })

  it('keeps a newly opened dialog open when an older delete succeeds', async () => {
    const { crud, fns } = createCrud()
    const deletedEntity = deferredPromise<void>()
    fns.deleteEntity.mockReturnValueOnce(deletedEntity.promise)

    crud.openEdit({ id: 5, name: 'Entity 5' })
    const deleting = crud.deleteSelected()

    crud.isOpen.value = false
    crud.openEdit({ id: 9, name: 'Entity 9' })
    deletedEntity.resolve()

    await deleting

    expect(fns.deleteEntity).toHaveBeenCalledWith(5)
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity deleted',
      description: 'Entity 5 was deleted.',
      variant: 'success',
    })
    expect(crud.isOpen.value).toBe(true)
    expect(crud.selected.value).toEqual({ id: 9, name: 'Entity 9' })
    expect(crud.isDeleting.value).toBe(false)
  })

  it('ignores stale delete errors after another dialog has opened', async () => {
    const { crud, fns, errors } = createCrud()
    const failedDelete = deferredPromise<void>()
    fns.deleteEntity.mockReturnValueOnce(failedDelete.promise)

    crud.openEdit({ id: 5, name: 'Entity 5' })
    const deleting = crud.deleteSelected()

    crud.isOpen.value = false
    crud.openEdit({ id: 9, name: 'Entity 9' })
    failedDelete.reject(new Error('old failure'))

    await deleting

    expect(errors.generalError.value).toBeNull()
    expect(mocks.toast).not.toHaveBeenCalled()
    expect(crud.isOpen.value).toBe(true)
    expect(crud.selected.value).toEqual({ id: 9, name: 'Entity 9' })
    expect(crud.isDeleting.value).toBe(false)
  })

  it('delegates delete conflicts to handleDeleteError and keeps the dialog open', async () => {
    const conflict = new Error('in use')
    const handleDeleteError = vi.fn((error: unknown) => error === conflict)
    const { crud, fns } = createCrud({ handleDeleteError })
    crud.openEdit({ id: 5, name: 'Entity 5' })
    fns.deleteEntity.mockRejectedValueOnce(conflict)

    await crud.deleteSelected()

    expect(handleDeleteError).toHaveBeenCalledWith(
      conflict,
      expect.objectContaining({ close: expect.any(Function) }),
    )
    expect(mocks.toast).not.toHaveBeenCalled()
    expect(crud.isOpen.value).toBe(true)
    expect(crud.selected.value).toEqual({ id: 5, name: 'Entity 5' })
    expect(crud.isDeleting.value).toBe(false)
  })

  it('closes the dialog with a toast and reloads when deleting hits a not-found error', async () => {
    const { crud, fns } = createCrud()
    crud.openEdit({ id: 5, name: 'Entity 5' })
    fns.deleteEntity.mockRejectedValueOnce(new TestNotFoundError('Entity gone'))

    await crud.deleteSelected()

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity not found',
      description: 'Entity gone',
      variant: 'destructive',
    })
    expect(crud.isOpen.value).toBe(false)
    expect(fns.onNotFound).toHaveBeenCalled()
  })

  it('sets the general error and keeps the dialog open on unexpected delete failures', async () => {
    const { crud, fns, errors } = createCrud()
    crud.openEdit({ id: 5, name: 'Entity 5' })
    fns.deleteEntity.mockRejectedValueOnce(new Error('boom'))

    await crud.deleteSelected()

    expect(errors.generalError.value).toBe('boom')
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Failed to delete entity',
      description: 'boom',
      variant: 'destructive',
    })
    expect(crud.isOpen.value).toBe(true)
  })

  it('loads the entity when opening the edit dialog by id', async () => {
    const { crud, fns } = createCrud()

    const opening = crud.openEditById(7)

    expect(crud.isOpen.value).toBe(true)
    expect(crud.isEditMode.value).toBe(true)
    expect(crud.isLoadingSelected.value).toBe(true)
    expect(crud.selected.value).toBeNull()

    await opening

    expect(fns.fetchEntity).toHaveBeenCalledWith(7)
    expect(crud.selected.value).toEqual({ id: 7, name: 'Entity 7' })
    expect(crud.isLoadingSelected.value).toBe(false)

    await crud.save({ name: 'Payload' })

    expect(fns.updateEntity).toHaveBeenCalledWith(7, { name: 'Payload' })
  })

  it('closes the dialog with a toast and reloads when loading by id hits a not-found error', async () => {
    const { crud, fns } = createCrud()
    fns.fetchEntity.mockRejectedValueOnce(new TestNotFoundError('Entity gone'))

    await crud.openEditById(7)

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity not found',
      description: 'Entity gone',
      variant: 'destructive',
    })
    expect(crud.isOpen.value).toBe(false)
    expect(fns.onNotFound).toHaveBeenCalled()
  })

  it('closes the dialog with a toast on unexpected load failures', async () => {
    const { crud, fns } = createCrud()
    fns.fetchEntity.mockRejectedValueOnce(new Error('boom'))

    await crud.openEditById(7)

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Failed to load entity',
      description: 'boom',
      variant: 'destructive',
    })
    expect(crud.isOpen.value).toBe(false)
    expect(crud.isLoadingSelected.value).toBe(false)
  })
})
