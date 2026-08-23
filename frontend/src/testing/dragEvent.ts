import { flushPromises } from '@vue/test-utils'
import { expect } from 'vitest'

export function createDragEvent(type: string) {
  const event = new Event(type, { bubbles: true, cancelable: true }) as Event & {
    dataTransfer: {
      effectAllowed: string
      dropEffect: string
      setData: (format: string, data: string) => void
    }
  }

  Object.defineProperty(event, 'dataTransfer', {
    value: {
      effectAllowed: '',
      dropEffect: '',
      setData: () => undefined,
    },
  })

  return event
}

/**
 * Drags the article named `sourceName` onto the row of `targetId` via the dedicated drag handle,
 * the way the admin article tables wire it up.
 */
export async function dragArticle(sourceName: string, targetId: number) {
  const handle = document.body.querySelector(
    `[aria-label="Drag article ${sourceName}"]`,
  ) as HTMLElement | null
  const target = document.body.querySelector(
    `[data-testid="article-drop-${targetId}"]`,
  ) as HTMLElement | null
  expect(handle).toBeTruthy()
  expect(target).toBeTruthy()

  handle?.dispatchEvent(createDragEvent('dragstart'))
  target?.dispatchEvent(createDragEvent('dragover'))
  target?.dispatchEvent(createDragEvent('drop'))
  await flushPromises()
}
