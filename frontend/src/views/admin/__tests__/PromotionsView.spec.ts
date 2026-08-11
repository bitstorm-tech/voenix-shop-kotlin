import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PromotionsView from '../PromotionsView.vue'
import type { AdminPromotionDto, UpsertAdminPromotionRequest } from '@/stores/admin/promotions'

const mocks = vi.hoisted(() => {
  class PromotionNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromotionNotFoundError'
    }
  }

  class PromotionCodeConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromotionCodeConflictError'
    }
  }

  class PromotionLockedError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromotionLockedError'
    }
  }

  class PromotionInUseError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromotionInUseError'
    }
  }

  return {
    toast: vi.fn(),
    storeState: {
      promotions: [] as AdminPromotionDto[],
      isLoading: false,
      error: null as string | null,
      fetchPromotions: vi.fn(),
      fetchPromotion: vi.fn(),
      createPromotion: vi.fn(),
      updatePromotion: vi.fn(),
      deletePromotion: vi.fn(),
    },
    PromotionNotFoundError,
    PromotionCodeConflictError,
    PromotionLockedError,
    PromotionInUseError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/promotions', () => ({
  useAdminPromotionsStore: () => mocks.storeState,
  PromotionNotFoundError: mocks.PromotionNotFoundError,
  PromotionCodeConflictError: mocks.PromotionCodeConflictError,
  PromotionLockedError: mocks.PromotionLockedError,
  PromotionInUseError: mocks.PromotionInUseError,
}))

// A response nests the discount; the request expectations below stay flat on purpose.
const summerPromotion: AdminPromotionDto = {
  id: 1,
  name: 'Summer',
  discount: { discountType: 'PERCENTAGE', discountValue: 10 },
  couponCode: 'SUMMER10',
  startsAt: null,
  endsAt: null,
  usageLimitTotal: 100,
  usageLimitPerUser: 1,
  isActive: true,
  redemptionCount: 0,
  isLocked: false,
}

const lockedPromotion: AdminPromotionDto = {
  ...summerPromotion,
  id: 2,
  name: 'Redeemed',
  couponCode: 'LOCKED10',
  redemptionCount: 1,
  isLocked: true,
}

function resetStoreState() {
  mocks.storeState.promotions = []
  mocks.storeState.isLoading = false
  mocks.storeState.error = null
  mocks.storeState.fetchPromotions.mockReset().mockResolvedValue(undefined)
  mocks.storeState.fetchPromotion.mockReset()
  mocks.storeState.createPromotion.mockReset()
  mocks.storeState.updatePromotion.mockReset()
  mocks.storeState.deletePromotion.mockReset()
}

async function mountPromotionsView() {
  const wrapper = mount(PromotionsView, {
    attachTo: document.body,
  })

  await flushPromises()
  return wrapper
}

function bodyText() {
  return document.body.textContent ?? ''
}

function queryButtonByText(text: string) {
  return [...document.body.querySelectorAll('button')].find((button) =>
    button.textContent?.includes(text),
  ) as HTMLButtonElement | undefined
}

async function clickButtonByText(text: string) {
  const button = queryButtonByText(text)
  expect(button).toBeTruthy()
  button?.click()
  await flushPromises()
}

async function clickBySelector(selector: string) {
  const element = document.body.querySelector(selector) as HTMLElement | null
  expect(element).toBeTruthy()
  element?.click()
  await flushPromises()
}

async function setFieldValue(selector: string, value: string) {
  const field = document.body.querySelector(selector) as HTMLInputElement | null
  expect(field).toBeTruthy()
  if (!field) {
    return
  }

  field.value = value
  field.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

function getFieldValue(selector: string) {
  const field = document.body.querySelector(selector) as HTMLInputElement | null
  expect(field).toBeTruthy()
  return field?.value
}

async function submitFieldForm(selector: string) {
  const field = document.body.querySelector(selector) as HTMLElement | null
  expect(field).toBeTruthy()
  const form = field?.closest('form')
  expect(form).toBeTruthy()
  form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
}

describe('PromotionsView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('loads and renders promotions', async () => {
    mocks.storeState.promotions = [summerPromotion, lockedPromotion]

    const wrapper = await mountPromotionsView()

    expect(mocks.storeState.fetchPromotions).toHaveBeenCalledTimes(1)
    expect(wrapper.find('h1').text()).toBe('Promotions')
    expect(bodyText()).toContain('Summer')
    expect(bodyText()).toContain('SUMMER10')
    expect(bodyText()).toContain('Locked')
  })

  it('renders the Promotions workflow actions and states', async () => {
    mocks.storeState.promotions = [summerPromotion, lockedPromotion]

    const wrapper = await mountPromotionsView()

    expect(wrapper.find('h1').text()).toBe('Promotions')
    expect(bodyText()).toContain('New Promotion')
    expect(bodyText()).toContain('Active')
    expect(bodyText()).toContain('Locked')
  })

  it('does not expose raw load errors in the Admin UI', async () => {
    mocks.storeState.error = 'Internal server detail'

    await mountPromotionsView()

    expect(bodyText()).toContain('Failed to load promotions.')
    expect(bodyText()).not.toContain('Internal server detail')
  })

  it('creates a promotion with a normalized payload', async () => {
    mocks.storeState.promotions = [summerPromotion]
    mocks.storeState.createPromotion.mockImplementation(
      async (payload: UpsertAdminPromotionRequest) => ({
        ...summerPromotion,
        id: 3,
        ...payload,
        startsAt: payload.startsAt ?? null,
        endsAt: payload.endsAt ?? null,
        usageLimitTotal: payload.usageLimitTotal ?? null,
        usageLimitPerUser: payload.usageLimitPerUser ?? null,
        redemptionCount: 0,
        isLocked: false,
      }),
    )

    await mountPromotionsView()
    await clickButtonByText('New Promotion')
    await setFieldValue('#promotion-name', '  Autumn  ')
    await setFieldValue('#promotion-coupon-code', ' autumn10 ')
    await setFieldValue('#promotion-discount-value', '10')
    await submitFieldForm('#promotion-name')

    expect(mocks.storeState.createPromotion).toHaveBeenCalledWith({
      name: 'Autumn',
      discountType: 'PERCENTAGE',
      discountValue: 10,
      couponCode: 'autumn10',
      startsAt: null,
      endsAt: null,
      usageLimitTotal: null,
      usageLimitPerUser: null,
      isActive: true,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Promotion created',
      description: 'Autumn was saved.',
      variant: 'success',
    })
  })

  it('maps duplicate coupon code errors into the dialog', async () => {
    mocks.storeState.promotions = [summerPromotion]
    mocks.storeState.createPromotion.mockRejectedValue(
      new mocks.PromotionCodeConflictError('Promotion code already exists'),
    )

    await mountPromotionsView()
    await clickButtonByText('New Promotion')
    await setFieldValue('#promotion-name', 'Summer Copy')
    await setFieldValue('#promotion-coupon-code', 'summer10')
    await setFieldValue('#promotion-discount-value', '10')
    await submitFieldForm('#promotion-name')

    expect(bodyText()).toContain('A Promotion with this Promotion Code already exists.')
    expect(mocks.toast).not.toHaveBeenCalled()
  })

  it('uses a generic fallback for unknown save errors', async () => {
    mocks.storeState.promotions = [summerPromotion]
    mocks.storeState.createPromotion.mockRejectedValue(new Error('Internal database detail'))

    await mountPromotionsView()
    await clickButtonByText('New Promotion')
    await setFieldValue('#promotion-name', 'Autumn')
    await setFieldValue('#promotion-coupon-code', 'AUTUMN10')
    await setFieldValue('#promotion-discount-value', '10')
    await submitFieldForm('#promotion-name')

    expect(bodyText()).toContain('Failed to save promotion.')
    expect(bodyText()).not.toContain('Internal database detail')
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Failed to save promotion',
      description: 'Failed to save promotion.',
      variant: 'destructive',
    })
  })

  it('opens edit prefilled and deletes unredeemed promotions', async () => {
    mocks.storeState.promotions = [summerPromotion]
    mocks.storeState.deletePromotion.mockResolvedValue(undefined)

    await mountPromotionsView()
    await clickBySelector('[aria-label="Edit promotion Summer"]')

    expect(getFieldValue('#promotion-name')).toBe('Summer')
    expect(getFieldValue('#promotion-coupon-code')).toBe('SUMMER10')

    await clickButtonByText('Delete Promotion')
    await clickBySelector('[data-testid="confirm-delete-promotion"]')

    expect(mocks.storeState.deletePromotion).toHaveBeenCalledWith(1)
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Promotion deleted',
      description: 'Summer was deleted.',
      variant: 'success',
    })
  })

  it('updates an unredeemed promotion from the edit dialog', async () => {
    mocks.storeState.promotions = [summerPromotion]
    mocks.storeState.updatePromotion.mockImplementation(
      async (id: number, payload: UpsertAdminPromotionRequest) => ({
        ...summerPromotion,
        id,
        ...payload,
        startsAt: payload.startsAt ?? null,
        endsAt: payload.endsAt ?? null,
        usageLimitTotal: payload.usageLimitTotal ?? null,
        usageLimitPerUser: payload.usageLimitPerUser ?? null,
        redemptionCount: 0,
        isLocked: false,
      }),
    )

    await mountPromotionsView()
    await clickBySelector('[aria-label="Edit promotion Summer"]')
    await setFieldValue('#promotion-name', 'Summer Updated')
    await submitFieldForm('#promotion-name')

    expect(mocks.storeState.updatePromotion).toHaveBeenCalledWith(1, {
      name: 'Summer Updated',
      discountType: 'PERCENTAGE',
      discountValue: 10,
      couponCode: 'SUMMER10',
      startsAt: null,
      endsAt: null,
      usageLimitTotal: 100,
      usageLimitPerUser: 1,
      isActive: true,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Promotion saved',
      description: 'Summer Updated was saved.',
      variant: 'success',
    })
  })

  it('refreshes the dialog into its locked state when the first redemption wins the save race', async () => {
    const newlyLockedPromotion: AdminPromotionDto = {
      ...summerPromotion,
      isActive: false,
      redemptionCount: 1,
      isLocked: true,
    }
    mocks.storeState.promotions = [summerPromotion]
    mocks.storeState.updatePromotion.mockRejectedValue(
      new mocks.PromotionLockedError('Promotion has redemptions and is locked'),
    )
    mocks.storeState.fetchPromotion.mockResolvedValue(newlyLockedPromotion)

    await mountPromotionsView()
    await clickBySelector('[aria-label="Edit promotion Summer"]')
    await setFieldValue('#promotion-name', 'Summer Updated')
    await submitFieldForm('#promotion-name')

    expect(mocks.storeState.fetchPromotion).toHaveBeenCalledWith(1)
    expect(bodyText()).toContain('Promotion has redemptions and is locked')
    expect(bodyText()).toContain('Only the active state can be changed.')
    const nameField = document.body.querySelector('#promotion-name') as HTMLInputElement
    expect(nameField.disabled).toBe(true)
    expect(nameField.value).toBe('Summer')
    expect((document.body.querySelector('#promotion-is-active') as HTMLInputElement).checked).toBe(
      false,
    )
    expect(queryButtonByText('Delete Promotion')).toBeUndefined()
  })

  // A `DELETE` conflict is its own refusal: the promotion is still in use. It is never the
  // "coupon code taken" case, so the view answers it with its own message and a fresh read.
  it('refreshes the dialog into its locked state when deletion discovers a redemption', async () => {
    const newlyLockedPromotion: AdminPromotionDto = {
      ...summerPromotion,
      redemptionCount: 1,
      isLocked: true,
    }
    mocks.storeState.promotions = [summerPromotion]
    mocks.storeState.deletePromotion.mockRejectedValue(
      new mocks.PromotionInUseError('Promotion is still in use and cannot be deleted'),
    )
    mocks.storeState.fetchPromotion.mockResolvedValue(newlyLockedPromotion)

    await mountPromotionsView()
    await clickBySelector('[aria-label="Edit promotion Summer"]')
    await clickButtonByText('Delete Promotion')
    await clickBySelector('[data-testid="confirm-delete-promotion"]')

    expect(mocks.storeState.fetchPromotion).toHaveBeenCalledWith(1)
    expect(bodyText()).toContain('This Promotion has been redeemed and can no longer be deleted.')
    expect(bodyText()).toContain('Only the active state can be changed.')
    expect((document.body.querySelector('#promotion-name') as HTMLInputElement).disabled).toBe(true)
    expect(queryButtonByText('Delete Promotion')).toBeUndefined()
  })

  it('lets locked promotions update only their active state', async () => {
    mocks.storeState.promotions = [lockedPromotion]
    mocks.storeState.updatePromotion.mockImplementation(
      async (id: number, payload: UpsertAdminPromotionRequest) => ({
        ...lockedPromotion,
        id,
        isActive: payload.isActive,
      }),
    )

    await mountPromotionsView()
    await clickBySelector('[aria-label="View promotion Redeemed"]')

    expect(bodyText()).toContain('Promotion details')
    expect(bodyText()).toContain('Only the active state can be changed.')
    await clickBySelector('#promotion-is-active')
    await clickButtonByText('Save Active State')

    expect(mocks.storeState.updatePromotion).toHaveBeenCalledWith(2, {
      name: 'Redeemed',
      discountType: 'PERCENTAGE',
      discountValue: 10,
      couponCode: 'LOCKED10',
      startsAt: null,
      endsAt: null,
      usageLimitTotal: 100,
      usageLimitPerUser: 1,
      isActive: false,
    })
    expect(queryButtonByText('Delete Promotion')).toBeUndefined()
  })
})
