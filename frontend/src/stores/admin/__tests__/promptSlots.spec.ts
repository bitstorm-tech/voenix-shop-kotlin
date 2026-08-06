import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  PromptSlotTypeInUseError,
  PromptSlotTypeNameConflictError,
  PromptSlotTypeNotFoundError,
  PromptSlotVariantInUseError,
  PromptSlotVariantNameConflictError,
  PromptSlotVariantNotFoundError,
  PromptSlotVariantSlotTypeNotFoundError,
  useAdminPromptSlotsStore,
} from '@/stores/admin/promptSlots'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

const subjectSlotType = {
  id: 1,
  name: 'Subject',
  position: 1,
  variantCount: 1,
}

const styleSlotType = {
  id: 2,
  name: 'Style',
  position: 2,
  variantCount: 0,
}

const portraitVariant = {
  id: 11,
  slotType: {
    id: 1,
    name: 'Subject',
    position: 1,
  },
  name: 'Portrait',
  prompt: 'portrait prompt',
  description: null,
  llm: null,
  assignedPromptCount: 0,
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

  it('loads slot types and variants from the admin APIs', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/admin/prompts/slot-types') {
        return jsonResponse({ items: [styleSlotType, subjectSlotType] })
      }

      return jsonResponse({
        items: [
          {
            id: 12,
            slotType: {
              id: 2,
              name: 'Style',
              position: 2,
            },
            name: 'Oil',
            prompt: 'oil prompt',
            description: 'Painterly style',
            llm: 'gpt-image',
            assignedPromptCount: 1,
          },
          portraitVariant,
        ],
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()

    await store.fetchSlotTypes()
    await store.fetchSlotVariants()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/prompts/slot-types')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/prompts/slot-variants')
    expect(store.slotTypes).toEqual([subjectSlotType, styleSlotType])
    expect(store.variantsBySlotTypeId[1]).toEqual([portraitVariant])
    expect(store.error).toBeNull()
  })

  it('creates slot types with antiforgery headers and syncs the sorted local list', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts/slot-types')
      expect(init?.method).toBe('POST')
      expect(init?.headers).toEqual({
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token-1',
      })
      expect(init?.body).toBe(JSON.stringify({ name: 'Background' }))
      return jsonResponse({
        id: 3,
        name: 'Background',
        position: 3,
        variantCount: 0,
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()
    store.slotTypes = [subjectSlotType]

    await store.createSlotType({ name: 'Background' })

    expect(store.slotTypes.map((slotType) => slotType.name)).toEqual(['Subject', 'Background'])
  })

  it('updates a slot type locally and refreshes embedded variant slot type summaries', async () => {
    const updatedSlotType = {
      ...subjectSlotType,
      name: 'Main Subject',
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(updatedSlotType)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()
    store.slotTypes = [subjectSlotType, styleSlotType]
    store.slotVariants = [portraitVariant]

    await store.updateSlotType(1, { name: 'Main Subject' })

    expect(store.slotTypes).toEqual([updatedSlotType, styleSlotType])
    expect(store.slotVariants.at(0)?.slotType).toEqual({
      id: 1,
      name: 'Main Subject',
      position: 1,
    })
  })

  it('syncs slot variants after create, update, and delete', async () => {
    const createdVariant = {
      id: 12,
      slotType: {
        id: 2,
        name: 'Style',
        position: 2,
      },
      name: 'Oil',
      prompt: 'oil prompt',
      description: 'Painterly style',
      llm: 'gpt-image',
      assignedPromptCount: 0,
    }
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
        return jsonResponse(createdVariant)
      }

      if (input === '/api/admin/prompts/slot-variants/12' && init?.method === 'PUT') {
        return jsonResponse(updatedVariant)
      }

      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()
    store.slotVariants = [portraitVariant]

    await store.createSlotVariant({
      slotTypeId: 2,
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
        slotTypeId: 2,
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
    expect(store.slotVariants).toEqual([updatedVariant])
  })

  it('removes deleted slot types and their variants locally after a successful delete', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()
    store.slotTypes = [subjectSlotType, styleSlotType]
    store.slotVariants = [portraitVariant]

    await store.deleteSlotType(1)

    expect(store.slotTypes).toEqual([styleSlotType])
    expect(store.slotVariants).toEqual([])
  })

  it('maps slot type save, delete, and not-found errors', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/slot-types/99') {
        return jsonResponse({ message: 'Prompt slot type not found' }, { status: 404 })
      }

      if (init?.method === 'DELETE') {
        return jsonResponse(
          { message: 'Prompt slot type has variants and cannot be deleted' },
          { status: 409 },
        )
      }

      return jsonResponse({ message: 'Prompt slot type name already exists' }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()

    await expect(store.createSlotType({ name: 'Subject' })).rejects.toBeInstanceOf(
      PromptSlotTypeNameConflictError,
    )
    await expect(store.deleteSlotType(1)).rejects.toBeInstanceOf(PromptSlotTypeInUseError)
    await expect(store.fetchSlotType(99)).rejects.toBeInstanceOf(PromptSlotTypeNotFoundError)
  })

  it('maps slot variant save, delete, and not-found errors', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/slot-variants/404') {
        return jsonResponse({ message: 'Prompt slot variant not found' }, { status: 404 })
      }

      if (input === '/api/admin/prompts/slot-variants/7' && init?.method === 'DELETE') {
        return jsonResponse(
          { message: 'Prompt slot variant is assigned to prompts' },
          { status: 409 },
        )
      }

      if (input === '/api/admin/prompts/slot-variants' && init?.method === 'POST') {
        return jsonResponse({ message: 'Prompt slot type not found' }, { status: 404 })
      }

      return jsonResponse(
        { message: 'Prompt slot variant name already exists for this slot type' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptSlotsStore()

    await expect(
      store.createSlotVariant({
        slotTypeId: 99,
        name: 'Missing',
        prompt: 'prompt',
        description: null,
        llm: null,
      }),
    ).rejects.toBeInstanceOf(PromptSlotVariantSlotTypeNotFoundError)
    await expect(
      store.updateSlotVariant(2, {
        name: 'Portrait',
        prompt: 'prompt',
        description: null,
        llm: null,
      }),
    ).rejects.toBeInstanceOf(PromptSlotVariantNameConflictError)
    await expect(store.deleteSlotVariant(7)).rejects.toBeInstanceOf(PromptSlotVariantInUseError)
    await expect(store.fetchSlotVariant(404)).rejects.toBeInstanceOf(PromptSlotVariantNotFoundError)
  })
})
