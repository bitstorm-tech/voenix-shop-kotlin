import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { useEntityCrud, type UseEntityCrudOptions } from '../useEntityCrud'
import { useFormErrors } from '../useFormErrors'

interface MockRoute {
  name: string
  params: Record<string, string | string[]>
}

const mocks = vi.hoisted(() => ({
  toast: vi.fn(),
  push: vi.fn(),
  replace: vi.fn(),
  route: null as unknown,
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.push, replace: mocks.replace }),
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

type TestOptions = UseEntityCrudOptions<TestEntity, TestPayload>

function defer<T>() {
  let resolve!: (value: T) => void
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
    resetForm: vi.fn(),
    fillForm: vi.fn(),
    validate: vi.fn(() => true),
    buildPayload: vi.fn((): TestPayload => ({ name: 'Payload' })),
  }

  const crud = useEntityCrud<TestEntity, TestPayload>({
    listRoute: { name: 'list' },
    notFoundError: TestNotFoundError,
    errors: { generalError: errors.generalError, clearErrors: errors.clearErrors },
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
    savedToast: (entity, isEditMode) => ({
      title: isEditMode ? 'Entity saved' : 'Entity created',
      description: `${entity.name} was saved.`,
    }),
    deletedToast: () => ({ title: 'Entity deleted', description: 'Entity was deleted.' }),
    ...fns,
    ...overrides,
  })

  return { crud, fns, errors }
}

function setRoute(params: Record<string, string | string[]>) {
  mocks.route = reactive({ name: 'edit', params })
}

function currentRoute() {
  return mocks.route as MockRoute
}

describe('useEntityCrud', () => {
  beforeEach(() => {
    mocks.toast.mockReset()
    mocks.push.mockReset()
    mocks.replace.mockReset()
    setRoute({})
  })

  it('creates the entity and redirects on save in create mode', async () => {
    const { crud, fns } = createCrud()
    await flushPromises()

    expect(crud.isEditMode.value).toBe(false)
    expect(fns.fetchEntity).not.toHaveBeenCalled()

    await crud.save()

    expect(fns.createEntity).toHaveBeenCalledWith({ name: 'Payload' })
    expect(fns.updateEntity).not.toHaveBeenCalled()
    expect(fns.fillForm).toHaveBeenCalledWith({ id: 99, name: 'Payload' })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity created',
      description: 'Payload was saved.',
      variant: 'success',
    })
    expect(mocks.push).toHaveBeenCalledWith({ name: 'list' })
    expect(crud.isSaving.value).toBe(false)
  })

  it('loads the entity and updates it on save in edit mode', async () => {
    setRoute({ id: '5' })
    const { crud, fns } = createCrud()
    await flushPromises()

    expect(fns.fetchEntity).toHaveBeenCalledWith(5)
    expect(fns.fillForm).toHaveBeenCalledWith({ id: 5, name: 'Entity 5' })
    expect(crud.isEditMode.value).toBe(true)

    await crud.save()

    expect(fns.updateEntity).toHaveBeenCalledWith(5, { name: 'Payload' })
    expect(fns.createEntity).not.toHaveBeenCalled()
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity saved',
      description: 'Payload was saved.',
      variant: 'success',
    })
    expect(mocks.push).toHaveBeenCalledWith({ name: 'list' })
  })

  it('does not save when validation fails', async () => {
    const { crud, fns } = createCrud({ validate: () => false })
    await flushPromises()

    await crud.save()

    expect(fns.createEntity).not.toHaveBeenCalled()
    expect(fns.updateEntity).not.toHaveBeenCalled()
    expect(mocks.toast).not.toHaveBeenCalled()
  })

  it('delegates save conflicts to handleSaveError without toast or redirect', async () => {
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
    await flushPromises()
    fns.createEntity.mockRejectedValueOnce(conflict)

    await crud.save()

    expect(handleSaveError).toHaveBeenCalledWith(conflict)
    expect(errors.fieldErrors.name).toBe('Name already exists.')
    expect(errors.generalError.value).toBeNull()
    expect(mocks.toast).not.toHaveBeenCalled()
    expect(mocks.push).not.toHaveBeenCalled()
    expect(crud.isSaving.value).toBe(false)
  })

  it('redirects with a toast when saving hits a not-found error', async () => {
    setRoute({ id: '5' })
    const { crud, fns } = createCrud()
    await flushPromises()
    mocks.toast.mockReset()
    fns.updateEntity.mockRejectedValueOnce(new TestNotFoundError('Entity gone'))

    await crud.save()

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity not found',
      description: 'Entity gone',
      variant: 'destructive',
    })
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'list' })
  })

  it('sets the general error on unexpected save failures', async () => {
    const { crud, fns, errors } = createCrud()
    await flushPromises()
    fns.createEntity.mockRejectedValueOnce(new Error('boom'))

    await crud.save()

    expect(errors.generalError.value).toBe('boom')
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Failed to save entity',
      description: 'boom',
      variant: 'destructive',
    })
    expect(mocks.push).not.toHaveBeenCalled()
  })

  it('deletes the entity, closes the dialog, and redirects', async () => {
    setRoute({ id: '5' })
    const { crud, fns } = createCrud()
    await flushPromises()
    mocks.toast.mockReset()
    crud.isDeleteDialogOpen.value = true

    await crud.deleteCurrent()

    expect(fns.deleteEntity).toHaveBeenCalledWith(5)
    expect(crud.isDeleteDialogOpen.value).toBe(false)
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity deleted',
      description: 'Entity was deleted.',
      variant: 'success',
    })
    expect(mocks.push).toHaveBeenCalledWith({ name: 'list' })
  })

  it('delegates delete conflicts to handleDeleteError and keeps the dialog state', async () => {
    setRoute({ id: '5' })
    const conflict = new Error('in use')
    const handleDeleteError = vi.fn((error: unknown) => error === conflict)
    const { crud, fns } = createCrud({ handleDeleteError })
    await flushPromises()
    mocks.toast.mockReset()
    fns.deleteEntity.mockRejectedValueOnce(conflict)
    crud.isDeleteDialogOpen.value = true

    await crud.deleteCurrent()

    expect(handleDeleteError).toHaveBeenCalledWith(
      conflict,
      expect.objectContaining({ closeDeleteDialog: expect.any(Function) }),
    )
    expect(crud.isDeleteDialogOpen.value).toBe(true)
    expect(mocks.toast).not.toHaveBeenCalled()
    expect(mocks.push).not.toHaveBeenCalled()
    expect(crud.isDeleting.value).toBe(false)
  })

  it('lets handleDeleteError close the delete dialog via the context', async () => {
    setRoute({ id: '5' })
    const conflict = new Error('in use')
    const { crud, fns } = createCrud({
      handleDeleteError: (error, { closeDeleteDialog }) => {
        if (error === conflict) {
          closeDeleteDialog()
          return true
        }
        return false
      },
    })
    await flushPromises()
    fns.deleteEntity.mockRejectedValueOnce(conflict)
    crud.isDeleteDialogOpen.value = true

    await crud.deleteCurrent()

    expect(crud.isDeleteDialogOpen.value).toBe(false)
  })

  it('redirects with a toast when the entity to load does not exist', async () => {
    setRoute({ id: '404' })
    createCrud({ fetchEntity: () => Promise.reject(new TestNotFoundError('Missing entity')) })
    await flushPromises()

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity not found',
      description: 'Missing entity',
      variant: 'destructive',
    })
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'list' })
  })

  it('redirects with a toast for invalid route ids without fetching', async () => {
    setRoute({ id: 'abc' })
    const { fns } = createCrud()
    await flushPromises()

    expect(fns.fetchEntity).not.toHaveBeenCalled()
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Entity not found',
      description: 'The requested entity does not exist.',
      variant: 'destructive',
    })
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'list' })
  })

  it('ignores stale load responses after navigating to another entity', async () => {
    setRoute({ id: '1' })
    const pendingOne = defer<TestEntity>()
    const pendingTwo = defer<TestEntity>()
    const fetchEntity = vi.fn((id: number) => (id === 1 ? pendingOne.promise : pendingTwo.promise))
    const { crud, fns } = createCrud({ fetchEntity })
    await flushPromises()

    currentRoute().params = { id: '2' }
    await flushPromises()

    expect(fetchEntity).toHaveBeenCalledTimes(2)
    expect(crud.isLoading.value).toBe(true)

    pendingTwo.resolve({ id: 2, name: 'Entity 2' })
    await flushPromises()

    expect(fns.fillForm).toHaveBeenCalledTimes(1)
    expect(fns.fillForm).toHaveBeenCalledWith({ id: 2, name: 'Entity 2' })
    expect(crud.isLoading.value).toBe(false)

    pendingOne.resolve({ id: 1, name: 'Entity 1' })
    await flushPromises()

    expect(fns.fillForm).toHaveBeenCalledTimes(1)
    expect(crud.isLoading.value).toBe(false)
  })

  it('ignores stale load failures after navigating away', async () => {
    setRoute({ id: '1' })
    const pendingOne = defer<TestEntity>()
    const fetchEntity = vi.fn((id: number) =>
      id === 1 ? pendingOne.promise : Promise.resolve({ id, name: `Entity ${id}` }),
    )
    const { errors } = createCrud({ fetchEntity })
    await flushPromises()

    currentRoute().params = { id: '2' }
    await flushPromises()

    pendingOne.reject(new Error('stale failure'))
    await flushPromises()

    expect(errors.generalError.value).toBeNull()
    expect(mocks.toast).not.toHaveBeenCalled()
  })
})
