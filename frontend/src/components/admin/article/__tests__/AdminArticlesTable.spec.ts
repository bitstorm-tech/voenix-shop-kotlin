import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import AdminArticlesTable from '../AdminArticlesTable.vue'
import type { AdminArticleListItemDto } from '@/stores/admin/articles'
import { createAdminArticleListItem as article } from '@/testing/adminArticle'
import { createDragEvent } from '@/testing/dragEvent'

async function mountTable(props: {
  articles: AdminArticleListItemDto[]
  reordering?: boolean
  reorderDisabled?: boolean
  syncColumn?: boolean
}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/articles/mugs',
        name: 'admin-mug-articles',
        component: { template: '<div />' },
      },
      {
        path: '/admin/articles/mugs/:id/edit',
        name: 'admin-mug-article-edit',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push('/admin/articles/mugs')
  await router.isReady()

  const wrapper = mount(AdminArticlesTable, {
    props: {
      articleType: 'MUG' as const,
      editRouteName: 'admin-mug-article-edit',
      ...props,
    },
    global: { plugins: [router] },
  })
  await flushPromises()

  return { router, wrapper }
}

interface PointerEventOptions {
  button?: number
  clientX?: number
  clientY?: number
  isPrimary?: boolean
  pointerId?: number
  pointerType?: 'mouse' | 'touch' | 'pen'
}

function createPointerEvent(type: string, options: PointerEventOptions = {}) {
  const {
    button = 0,
    clientX = 0,
    clientY = 0,
    isPrimary = true,
    pointerId = 7,
    pointerType = 'touch',
  } = options
  const event = new Event(type, { bubbles: true, cancelable: true }) as Event & {
    button: number
    clientX: number
    clientY: number
    isPrimary: boolean
    pointerId: number
    pointerType: string
  }
  Object.defineProperties(event, {
    button: { value: button },
    clientX: { value: clientX },
    clientY: { value: clientY },
    isPrimary: { value: isPrimary },
    pointerId: { value: pointerId },
    pointerType: { value: pointerType },
  })
  return event
}

function installPointerEnvironment(handle: Element, hitTest: () => Element | null) {
  const setPointerCapture = vi.fn()
  const releasePointerCapture = vi.fn()
  Object.defineProperties(handle, {
    releasePointerCapture: { configurable: true, value: releasePointerCapture },
    setPointerCapture: { configurable: true, value: setPointerCapture },
  })

  const originalElementFromPoint = Object.getOwnPropertyDescriptor(document, 'elementFromPoint')
  Object.defineProperty(document, 'elementFromPoint', {
    configurable: true,
    value: vi.fn(hitTest),
  })

  return {
    releasePointerCapture,
    setPointerCapture,
    restore() {
      if (originalElementFromPoint) {
        Object.defineProperty(document, 'elementFromPoint', originalElementFromPoint)
      } else {
        Reflect.deleteProperty(document, 'elementFromPoint')
      }
    },
  }
}

describe('AdminArticlesTable', () => {
  it('renders a hover-preview thumbnail with the resized variant example image', async () => {
    const { wrapper } = await mountTable({
      articles: [article({ exampleImageFilename: 'white.webp' })],
    })

    const thumbnail = wrapper.get('[data-testid="example-image-thumbnail"]')

    expect(thumbnail.attributes('src')).toBe(
      '/api/images/public/80/articles/mugs/variant-example-images/white.webp',
    )
    expect(thumbnail.attributes('alt')).toBe('Classic Mug')
    expect(thumbnail.attributes('data-state')).toBe('closed')
    expect(wrapper.find('[data-testid="example-image-placeholder"]').exists()).toBe(false)
  })

  it('renders the placeholder without a hover-preview trigger when no variant image exists', async () => {
    const { wrapper } = await mountTable({ articles: [article()] })

    expect(wrapper.find('[data-testid="example-image-placeholder"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="example-image-thumbnail"]').exists()).toBe(false)
  })

  it('falls back to the placeholder when the thumbnail fails to load', async () => {
    const { wrapper } = await mountTable({
      articles: [article({ exampleImageFilename: 'broken.webp' })],
    })

    await wrapper.get('[data-testid="example-image-thumbnail"]').trigger('error')

    expect(wrapper.find('[data-testid="example-image-thumbnail"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="example-image-placeholder"]').exists()).toBe(true)
  })

  it('renders the image column between order and name', async () => {
    const { wrapper } = await mountTable({ articles: [article()] })

    const headers = wrapper.findAll('th').map((header) => header.text())

    expect(headers).toEqual([
      'Order',
      'Image',
      'Name',
      'Category',
      'Supplier',
      'Variants',
      'Status',
      'Actions',
    ])
  })

  it('emits source and target ids from a dedicated drag handle without mutating its input', async () => {
    const articles = [
      article({ id: 1, position: 1, name: 'First' }),
      article({ id: 2, position: 2, name: 'Second', active: false }),
    ]
    const originalArticles = structuredClone(articles)
    const { wrapper } = await mountTable({ articles })
    const handle = wrapper.get('[aria-label="Drag article Second"]')
    const target = wrapper.get('[data-testid="article-drop-1"]')

    handle.element.dispatchEvent(createDragEvent('dragstart'))
    target.element.dispatchEvent(createDragEvent('dragover'))
    await flushPromises()

    expect(wrapper.get('[data-testid="article-drop-2"]').classes()).toContain('opacity-50')
    expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(true)

    target.element.dispatchEvent(createDragEvent('drop'))
    await flushPromises()

    expect(wrapper.emitted('reorderArticles')).toEqual([[2, 1]])
    expect(articles).toEqual(originalArticles)
  })

  it.each([false, true])(
    'spans the drop skeleton across every column of the table (syncColumn %s)',
    async (syncColumn) => {
      const articles = [
        article({ id: 1, position: 1, name: 'First' }),
        article({ id: 2, position: 2, name: 'Second' }),
      ]
      const { wrapper } = await mountTable({ articles, syncColumn })

      wrapper
        .get('[aria-label="Drag article Second"]')
        .element.dispatchEvent(createDragEvent('dragstart'))
      wrapper
        .get('[data-testid="article-drop-1"]')
        .element.dispatchEvent(createDragEvent('dragover'))
      await flushPromises()

      const columnCount = wrapper.findAll('thead th').length
      expect(columnCount).toBe(syncColumn ? 9 : 8)
      expect(wrapper.get('[data-testid="article-drop-skeleton"] td').attributes('colspan')).toBe(
        String(columnCount),
      )
    },
  )

  it('disables only the reorder interaction while an order is being saved', async () => {
    const { router, wrapper } = await mountTable({
      articles: [article({ id: 1, name: 'First' }), article({ id: 2, name: 'Second' })],
      reordering: true,
    })
    const handle = wrapper.get('[aria-label="Drag article Second"]')
    const target = wrapper.get('[data-testid="article-drop-1"]')

    expect(handle.attributes('disabled')).toBeDefined()
    expect(handle.attributes('draggable')).toBe('false')
    expect(wrapper.get('[role="status"]').text()).toBe('Saving article order...')

    handle.element.dispatchEvent(createDragEvent('dragstart'))
    target.element.dispatchEvent(createDragEvent('drop'))
    await target.findAll('td')[0]!.trigger('click')
    await flushPromises()

    expect(wrapper.emitted('reorderArticles')).toBeUndefined()
    expect(router.currentRoute.value.name).toBe('admin-mug-articles')
  })

  it('ignores row drags and emits a downward move only after its handle starts the drag', async () => {
    const { wrapper } = await mountTable({
      articles: [article({ id: 1, name: 'First' }), article({ id: 2, name: 'Second' })],
    })
    const sourceRow = wrapper.get('[data-testid="article-drop-1"]')
    const target = wrapper.get('[data-testid="article-drop-2"]')

    sourceRow.element.dispatchEvent(createDragEvent('dragstart'))
    target.element.dispatchEvent(createDragEvent('dragover'))
    target.element.dispatchEvent(createDragEvent('drop'))
    await flushPromises()
    expect(wrapper.emitted('reorderArticles')).toBeUndefined()

    wrapper
      .get('[aria-label="Drag article First"]')
      .element.dispatchEvent(createDragEvent('dragstart'))
    target.element.dispatchEvent(createDragEvent('dragover'))
    await flushPromises()
    expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(true)

    target.element.dispatchEvent(createDragEvent('drop'))
    await flushPromises()
    expect(wrapper.emitted('reorderArticles')).toEqual([[1, 2]])
  })

  it.each(['touch', 'pen'] as const)(
    'reorders with a %s pointer from the dedicated handle',
    async (pointerType) => {
      const { router, wrapper } = await mountTable({
        articles: [article({ id: 1, name: 'First' }), article({ id: 2, name: 'Second' })],
      })
      const handle = wrapper.get('[aria-label="Drag article Second"]')
      const target = wrapper.get('[data-testid="article-drop-1"]')
      const pointerEnvironment = installPointerEnvironment(handle.element, () => target.element)

      try {
        const pointerDown = createPointerEvent('pointerdown', { pointerType })
        handle.element.dispatchEvent(pointerDown)
        expect(pointerDown.defaultPrevented).toBe(true)
        expect(pointerEnvironment.setPointerCapture).toHaveBeenCalledWith(7)

        const pointerMove = createPointerEvent('pointermove', {
          clientX: 10,
          clientY: 20,
          pointerType,
        })
        handle.element.dispatchEvent(pointerMove)
        await flushPromises()

        expect(pointerMove.defaultPrevented).toBe(true)
        expect(wrapper.get('[data-testid="article-drop-2"]').classes()).toContain('opacity-50')
        expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(true)

        const pointerUp = createPointerEvent('pointerup', {
          clientX: 10,
          clientY: 20,
          pointerType,
        })
        handle.element.dispatchEvent(pointerUp)
        await flushPromises()

        expect(pointerUp.defaultPrevented).toBe(true)
        expect(pointerEnvironment.releasePointerCapture).toHaveBeenCalledWith(7)
        expect(wrapper.emitted('reorderArticles')).toEqual([[2, 1]])

        await handle.trigger('click')
        await flushPromises()
        expect(router.currentRoute.value.name).toBe('admin-mug-articles')
      } finally {
        pointerEnvironment.restore()
      }
    },
  )

  it('does not reorder or navigate when a touch handle is only tapped', async () => {
    const { router, wrapper } = await mountTable({
      articles: [article({ id: 1, name: 'First' }), article({ id: 2, name: 'Second' })],
    })
    const handle = wrapper.get('[aria-label="Drag article Second"]')
    const target = wrapper.get('[data-testid="article-drop-1"]')
    const pointerEnvironment = installPointerEnvironment(handle.element, () => target.element)

    try {
      handle.element.dispatchEvent(createPointerEvent('pointerdown'))
      handle.element.dispatchEvent(createPointerEvent('pointerup'))
      await handle.trigger('click')
      await flushPromises()

      expect(wrapper.emitted('reorderArticles')).toBeUndefined()
      expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(false)
      expect(wrapper.get('[data-testid="article-drop-2"]').classes()).not.toContain('opacity-50')
      expect(router.currentRoute.value.name).toBe('admin-mug-articles')
    } finally {
      pointerEnvironment.restore()
    }
  })

  it.each(['outside the table', 'the source row', 'a removed target'] as const)(
    'cancels a pointer reorder when released over %s',
    async (releaseTarget) => {
      const articles = [article({ id: 1, name: 'First' }), article({ id: 2, name: 'Second' })]
      const { wrapper } = await mountTable({ articles })
      const handle = wrapper.get('[aria-label="Drag article Second"]')
      const source = wrapper.get('[data-testid="article-drop-2"]')
      const target = wrapper.get('[data-testid="article-drop-1"]')
      let pointedElement: Element | null = target.element
      const pointerEnvironment = installPointerEnvironment(handle.element, () => pointedElement)

      try {
        handle.element.dispatchEvent(createPointerEvent('pointerdown'))
        handle.element.dispatchEvent(
          createPointerEvent('pointermove', { clientX: 10, clientY: 20 }),
        )
        await flushPromises()
        expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(true)

        if (releaseTarget === 'outside the table') {
          pointedElement = null
        } else if (releaseTarget === 'the source row') {
          pointedElement = source.element
        } else {
          pointedElement = target.element
          await wrapper.setProps({ articles: [articles[1]!] })
        }

        handle.element.dispatchEvent(createPointerEvent('pointerup', { clientX: 10, clientY: 20 }))
        await flushPromises()

        expect(wrapper.emitted('reorderArticles')).toBeUndefined()
        expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(false)
        expect(wrapper.get('[data-testid="article-drop-2"]').classes()).not.toContain('opacity-50')
      } finally {
        pointerEnvironment.restore()
      }
    },
  )

  it('abandons a pointer gesture when pointer capture fails', async () => {
    const { wrapper } = await mountTable({
      articles: [article({ id: 1, name: 'First' }), article({ id: 2, name: 'Second' })],
    })
    const handle = wrapper.get('[aria-label="Drag article Second"]')
    Object.defineProperty(handle.element, 'setPointerCapture', {
      configurable: true,
      value: vi.fn(() => {
        throw new DOMException('Pointer is no longer active')
      }),
    })

    handle.element.dispatchEvent(createPointerEvent('pointerdown'))
    handle.element.dispatchEvent(createPointerEvent('pointermove', { clientX: 10, clientY: 20 }))
    handle.element.dispatchEvent(createPointerEvent('pointerup', { clientX: 10, clientY: 20 }))
    await flushPromises()

    expect(wrapper.emitted('reorderArticles')).toBeUndefined()
    expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(false)
  })

  it.each(['pointercancel', 'lostpointercapture'])(
    'clears touch reorder state on %s',
    async (cleanupEvent) => {
      const { wrapper } = await mountTable({
        articles: [article({ id: 1, name: 'First' }), article({ id: 2, name: 'Second' })],
      })
      const handle = wrapper.get('[aria-label="Drag article Second"]')
      const target = wrapper.get('[data-testid="article-drop-1"]')
      const pointerEnvironment = installPointerEnvironment(handle.element, () => target.element)

      try {
        handle.element.dispatchEvent(createPointerEvent('pointerdown'))
        handle.element.dispatchEvent(
          createPointerEvent('pointermove', { clientX: 10, clientY: 20 }),
        )
        await flushPromises()
        expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(true)

        handle.element.dispatchEvent(createPointerEvent(cleanupEvent))
        handle.element.dispatchEvent(createPointerEvent('pointerup', { clientX: 10, clientY: 20 }))
        await flushPromises()

        expect(wrapper.emitted('reorderArticles')).toBeUndefined()
        expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(false)
        expect(wrapper.get('[data-testid="article-drop-2"]').classes()).not.toContain('opacity-50')
      } finally {
        pointerEnvironment.restore()
      }
    },
  )

  it('cancels an active pointer reorder when reordering becomes disabled', async () => {
    const { wrapper } = await mountTable({
      articles: [article({ id: 1, name: 'First' }), article({ id: 2, name: 'Second' })],
    })
    const handle = wrapper.get('[aria-label="Drag article Second"]')
    const target = wrapper.get('[data-testid="article-drop-1"]')
    const pointerEnvironment = installPointerEnvironment(handle.element, () => target.element)

    try {
      handle.element.dispatchEvent(createPointerEvent('pointerdown'))
      handle.element.dispatchEvent(createPointerEvent('pointermove', { clientX: 10, clientY: 20 }))
      await flushPromises()
      expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(true)

      await wrapper.setProps({ reorderDisabled: true })
      handle.element.dispatchEvent(createPointerEvent('pointerup', { clientX: 10, clientY: 20 }))
      await flushPromises()

      expect(wrapper.emitted('reorderArticles')).toBeUndefined()
      expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(false)
    } finally {
      pointerEnvironment.restore()
    }
  })

  it('ignores mouse, non-primary, right-button, and additional pointer gestures', async () => {
    const { wrapper } = await mountTable({
      articles: [article({ id: 1, name: 'First' }), article({ id: 2, name: 'Second' })],
    })
    const handle = wrapper.get('[aria-label="Drag article Second"]')
    const target = wrapper.get('[data-testid="article-drop-1"]')
    const pointerEnvironment = installPointerEnvironment(handle.element, () => target.element)

    try {
      handle.element.dispatchEvent(createPointerEvent('pointerdown', { pointerType: 'mouse' }))
      handle.element.dispatchEvent(createPointerEvent('pointerdown', { isPrimary: false }))
      handle.element.dispatchEvent(createPointerEvent('pointerdown', { button: 2 }))

      handle.element.dispatchEvent(createPointerEvent('pointerdown', { pointerId: 7 }))
      handle.element.dispatchEvent(
        createPointerEvent('pointermove', { clientX: 10, clientY: 20, pointerId: 8 }),
      )
      handle.element.dispatchEvent(
        createPointerEvent('pointerup', { clientX: 10, clientY: 20, pointerId: 8 }),
      )
      handle.element.dispatchEvent(createPointerEvent('pointercancel', { pointerId: 7 }))
      await flushPromises()

      expect(wrapper.emitted('reorderArticles')).toBeUndefined()
      expect(wrapper.find('[data-testid="article-drop-skeleton"]').exists()).toBe(false)
    } finally {
      pointerEnvironment.restore()
    }
  })

  it('keeps row and edit navigation independent from the reorder handle', async () => {
    const { router, wrapper } = await mountTable({
      articles: [article({ id: 1, name: 'First' })],
    })
    const row = wrapper.get('[data-testid="article-drop-1"]')

    await row.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.params.id).toBe('1')

    await router.push('/admin/articles/mugs')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(router.currentRoute.value.params.id).toBe('1')

    await router.push('/admin/articles/mugs')
    await row.trigger('keydown', { key: ' ' })
    await flushPromises()
    expect(router.currentRoute.value.params.id).toBe('1')

    await router.push('/admin/articles/mugs')
    await wrapper.get('[aria-label="Edit article First"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.params.id).toBe('1')

    await router.push('/admin/articles/mugs')
    const handle = wrapper.get('[aria-label="Drag article First"]')
    await handle.trigger('click')
    await handle.trigger('keydown', { key: 'Enter' })
    await handle.trigger('keydown', { key: ' ' })
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('admin-mug-articles')
  })
})
