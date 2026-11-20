import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { ApiError } from '@/lib/api'
import { AccessTokenNotFoundError, type AccessToken } from '@/stores/admin/accessTokens'
import AccessTokensView from '../AccessTokensView.vue'

const tokensState = reactive({
  tokens: [] as AccessToken[],
  isLoading: false,
  error: null as Error | null,
  isCreating: false,
  revokingId: null as number | null,
  fetchTokens: vi.fn(),
  createToken: vi.fn(),
  revokeToken: vi.fn(),
})

vi.mock('@/stores/admin/accessTokens', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/admin/accessTokens')>()
  return { ...actual, useAdminAccessTokensStore: () => tokensState }
})

const used: AccessToken = {
  id: 1,
  name: 'Codex',
  userEmail: 'admin@voenix.example',
  createdAt: '2026-11-01T10:00:00.000Z',
  lastUsedAt: '2026-11-19T08:00:00.000Z',
}
const unused: AccessToken = {
  id: 2,
  name: 'Claude Code',
  userEmail: 'other@voenix.example',
  createdAt: '2026-11-20T10:00:00.000Z',
  lastUsedAt: null,
}

async function mountView() {
  const wrapper = mount(AccessTokensView, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

async function create(wrapper: Awaited<ReturnType<typeof mountView>>, name: string) {
  await wrapper.get('#access-token-name').setValue(name)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

const confirmButton = () =>
  document.body.querySelector<HTMLButtonElement>('[data-testid="confirm-revoke"]')

describe('AccessTokensView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    tokensState.tokens = []
    tokensState.isLoading = false
    tokensState.error = null
    tokensState.isCreating = false
    tokensState.revokingId = null
    tokensState.fetchTokens = vi.fn()
    tokensState.createToken = vi.fn()
    tokensState.revokeToken = vi.fn()
  })

  it('loads the tokens and shows name, owner, created, and last used', async () => {
    tokensState.tokens = [unused, used]
    const wrapper = await mountView()

    expect(tokensState.fetchTokens).toHaveBeenCalledOnce()
    const headers = wrapper.findAll('th').map((cell) => cell.text())
    expect(headers).toEqual(['Name', 'Owner', 'Created', 'Last used', 'Actions'])
    const rows = wrapper.findAll('[data-testid="access-token"]')
    expect(rows).toHaveLength(2)
    const cells = rows[0]!.findAll('td').map((cell) => cell.text())
    expect(cells.slice(0, 2)).toEqual(['Claude Code', 'other@voenix.example'])
    expect(cells[2]).toContain('2026')
    expect(cells[3]).toBe('never')
    expect(rows[1]!.findAll('td')[3]!.text()).not.toBe('never')
  })

  it('shows a created token once, with the endpoint and the command, until the panel closes', async () => {
    tokensState.createToken = vi.fn(async () => ({ ...unused, token: 'vnx_secret' }))
    const wrapper = await mountView()

    await create(wrapper, 'Claude Code')

    expect(tokensState.createToken).toHaveBeenCalledWith('Claude Code')
    const panel = wrapper.get('[data-testid="created-token"]')
    const endpoint = `${window.location.origin}/api/mcp`
    expect(panel.text()).toContain('vnx_secret')
    expect(panel.text()).toContain(endpoint)
    expect(panel.text()).toContain(
      `claude mcp add --transport http voenix-admin ${endpoint} --header "Authorization: Bearer vnx_secret"`,
    )
    expect(panel.text()).toContain('It cannot be shown again')
    expect((wrapper.get('#access-token-name').element as HTMLInputElement).value).toBe('')

    await wrapper.get('[data-testid="close-created-token"]').trigger('click')

    expect(wrapper.find('[data-testid="created-token"]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('vnx_secret')
  })

  it('shows the backend field error of a refused name at the field', async () => {
    tokensState.createToken = vi.fn(async () => {
      throw new ApiError('Validation failed', 400, { errors: { name: ['Name is required'] } })
    })
    const wrapper = await mountView()

    await create(wrapper, '')

    expect(wrapper.get('[data-form-error]').text()).toBe('Name is required')
    expect(wrapper.find('[data-testid="created-token"]').exists()).toBe(false)
  })

  it('shows any other create failure as an alert', async () => {
    tokensState.createToken = vi.fn(async () => {
      throw new ApiError('Internal server error', 500, null)
    })
    const wrapper = await mountView()

    await create(wrapper, 'Claude Code')

    expect(wrapper.find('[data-form-error]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Internal server error')
  })

  it('revokes a token after the confirmation', async () => {
    tokensState.tokens = [unused]
    const wrapper = await mountView()

    await wrapper.get('[data-testid="revoke-2"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('Revoke Claude Code?')
    expect(tokensState.revokeToken).not.toHaveBeenCalled()
    confirmButton()!.click()
    await flushPromises()

    expect(tokensState.revokeToken).toHaveBeenCalledWith(2)
    expect(tokensState.fetchTokens).toHaveBeenCalledOnce()
  })

  it('reloads the list when the token was gone already', async () => {
    tokensState.tokens = [unused]
    tokensState.revokeToken = vi.fn(async () => {
      throw new AccessTokenNotFoundError('Access token not found')
    })
    const wrapper = await mountView()

    await wrapper.get('[data-testid="revoke-2"]').trigger('click')
    await flushPromises()
    confirmButton()!.click()
    await flushPromises()

    expect(tokensState.fetchTokens).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('shows a revoke failure and a load failure as alerts', async () => {
    tokensState.tokens = [unused]
    tokensState.revokeToken = vi.fn(async () => {
      throw new ApiError('Internal server error', 500, null)
    })
    const wrapper = await mountView()

    await wrapper.get('[data-testid="revoke-2"]').trigger('click')
    await flushPromises()
    confirmButton()!.click()
    await flushPromises()
    expect(wrapper.text()).toContain('Internal server error')

    tokensState.error = new Error('Admin access required')
    await flushPromises()
    expect(wrapper.text()).toContain('Admin access required')
  })
})
