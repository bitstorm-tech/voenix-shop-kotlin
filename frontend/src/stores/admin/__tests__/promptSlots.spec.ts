import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  type AdminPromptSlotVariantDto,
  PromptSlotInUseError,
  PromptSlotNameConflictError,
  PromptSlotNotFoundError,
  PromptSlotValidationError,
  PromptSlotVariantInUseError,
  PromptSlotVariantNameConflictError,
  PromptSlotVariantNotFoundError,
  useAdminPromptSlotsStore,
} from '@/stores/admin/promptSlots'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

const subjectSlot = {
  id: 1,
  name: 'Subject',
  position: 1,
  variantCount: 1,
}

const styleSlot = {
  id: 2,
  name: 'Style',
  position: 2,
  variantCount: 0,
}

function variant(overrides: Partial<AdminPromptSlotVariantDto> = {}): AdminPromptSlotVariantDto {
  return {
    id: 11,
    slotId: 1,
    slotName: 'Subject',
    name: 'Portrait',
    prompt: 'portrait prompt',
    description: null,
    llm: null,
    assignedPromptCount: 0,
    ...overrides,
  }
}

describe('admin prompt slots store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads slots and variants from the renamed route and the bare array answers', async () => {
    const oilVariant = variant({
      id: 12,
      slotId: 2,
      slotName: 'Style',
      name: 'Oil',
      prompt: 'oil prompt',
      description: 'Painterly style',
      llm: 'gpt-image',
      assignedPromptCount: 1,
    })
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/admin/prompts/slots') {
        return jsonResponse([styleSlot, subjectSlot])
      }

      return jsonResponse([oilVariant, variant()])
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()

    await store.fetchSlots()
    await store.fetchSlotVariants()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/prompts/slots')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/prompts/slot-variants')
    expect(store.slots).toEqual([subjectSlot, styleSlot])
    expect(store.variantsBySlotId[1]).toEqual([variant()])
    expect(store.variantsBySlotId[2]).toEqual([oilVariant])
    expect(store.error).toBeNull()
  })

  it('creates slots on the slots route with antiforgery headers and sorts the local list', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts/slots')
      expect(init?.method).toBe('POST')
      expect(init?.headers).toEqual({
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token-1',
      })
      expect(init?.body).toBe(JSON.stringify({ name: 'Background' }))
      return jsonResponse(
        { id: 3, name: 'Background', position: 3, variantCount: 0 },
        { status: 201 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()
    store.slots = [subjectSlot]

    await store.createSlot({ name: 'Background' })

    expect(store.slots.map((slot) => slot.name)).toEqual(['Subject', 'Background'])
  })

  it('updates a slot on the slots route and refreshes the flat slot name of its variants', async () => {
    const updatedSlot = { ...subjectSlot, name: 'Main Subject' }
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts/slots/1')
      return jsonResponse(updatedSlot)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()
    store.slots = [subjectSlot, styleSlot]
    store.slotVariants = [variant()]

    await store.updateSlot(1, { name: 'Main Subject' })

    expect(store.slots).toEqual([updatedSlot, styleSlot])
    expect(store.slotVariants.at(0)?.slotName).toBe('Main Subject')
    expect(store.slotVariants.at(0)?.slotId).toBe(1)
  })

  it('sends slotId on a create and no slotId at all on an update', async () => {
    const createdVariant = variant({
      id: 12,
      slotId: 2,
      slotName: 'Style',
      name: 'Oil',
      prompt: 'oil prompt',
      description: 'Painterly style',
      llm: 'gpt-image',
    })
    const updatedVariant = {
      ...createdVariant,
      name: 'Expressive Oil',
      prompt: 'expressive oil prompt',
      description: null,
      llm: null,
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/slot-variants' && init?.method === 'POST') {
        return jsonResponse(createdVariant, { status: 201 })
      }

      if (input === '/api/admin/prompts/slot-variants/12' && init?.method === 'PUT') {
        return jsonResponse(updatedVariant)
      }

      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()
    store.slotVariants = [variant()]

    await store.createSlotVariant({
      slotId: 2,
      name: 'Oil',
      prompt: 'oil prompt',
      description: 'Painterly style',
      llm: 'gpt-image',
    })
    await store.updateSlotVariant(12, {
      name: 'Expressive Oil',
      prompt: 'expressive oil prompt',
      description: null,
      llm: null,
    })
    await store.deleteSlotVariant(11)

    const createCall = fetchMock.mock.calls.find(
      ([input, init]) => input === '/api/admin/prompts/slot-variants' && init?.method === 'POST',
    )
    expect(createCall?.[1]?.body).toBe(
      JSON.stringify({
        slotId: 2,
        name: 'Oil',
        prompt: 'oil prompt',
        description: 'Painterly style',
        llm: 'gpt-image',
      }),
    )
    const updateCall = fetchMock.mock.calls.find(
      ([input, init]) => input === '/api/admin/prompts/slot-variants/12' && init?.method === 'PUT',
    )
    expect(updateCall?.[1]?.body).toBe(
      JSON.stringify({
        name: 'Expressive Oil',
        prompt: 'expressive oil prompt',
        description: null,
        llm: null,
      }),
    )
    expect(JSON.parse(String(updateCall?.[1]?.body))).not.toHaveProperty('slotId')
    expect(store.slotVariants).toEqual([updatedVariant])
  })

  it('removes deleted slots and their variants locally after a successful delete', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts/slots/1')
      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()
    store.slots = [subjectSlot, styleSlot]
    store.slotVariants = [variant()]

    await store.deleteSlot(1)

    expect(store.slots).toEqual([styleSlot])
    expect(store.slotVariants).toEqual([])
  })

  it('discriminates the slot 409 by route: a write is the name, a delete is "in use"', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/slots/99') {
        return jsonResponse({ message: 'Prompt slot not found' }, { status: 404 })
      }

      if (init?.method === 'DELETE') {
        return jsonResponse(
          { message: 'Prompt slot is used by slot variants and cannot be deleted' },
          { status: 409 },
        )
      }

      return jsonResponse({ message: 'Prompt slot name already exists' }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()

    await expect(store.createSlot({ name: 'Subject' })).rejects.toBeInstanceOf(
      PromptSlotNameConflictError,
    )
    await expect(store.deleteSlot(1)).rejects.toMatchObject({
      name: 'PromptSlotInUseError',
      message: 'Prompt slot is used by slot variants and cannot be deleted',
    })
    await expect(store.deleteSlot(1)).rejects.toBeInstanceOf(PromptSlotInUseError)
    await expect(store.fetchSlot(99)).rejects.toBeInstanceOf(PromptSlotNotFoundError)
  })

  it('reports an unknown slot on a variant create as a 400 field error on slotId', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        {
          message: 'Validation failed',
          errors: { slotId: ['Prompt slot does not exist'] },
        },
        { status: 400 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()

    const error = await store
      .createSlotVariant({ slotId: 99, name: 'Missing', prompt: 'prompt' })
      .catch((err: unknown) => err)

    expect(error).toBeInstanceOf(PromptSlotValidationError)
    expect((error as PromptSlotValidationError).fieldError('slotId')).toBe(
      'Prompt slot does not exist',
    )
  })

  it('discriminates the variant 409 by route: a write is the globally unique name', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/slot-variants/404') {
        return jsonResponse({ message: 'Prompt slot variant not found' }, { status: 404 })
      }

      if (input === '/api/admin/prompts/slot-variants/7' && init?.method === 'DELETE') {
        return jsonResponse(
          { message: 'Prompt slot variant is used by prompts and cannot be deleted' },
          { status: 409 },
        )
      }

      return jsonResponse({ message: 'Prompt slot variant name already exists' }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()

    await expect(
      store.updateSlotVariant(2, { name: 'Portrait', prompt: 'prompt' }),
    ).rejects.toBeInstanceOf(PromptSlotVariantNameConflictError)
    await expect(store.deleteSlotVariant(7)).rejects.toBeInstanceOf(PromptSlotVariantInUseError)
    await expect(store.fetchSlotVariant(404)).rejects.toBeInstanceOf(PromptSlotVariantNotFoundError)
  })
})
