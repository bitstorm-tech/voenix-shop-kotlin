import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { useClipboardImagePaste } from '../useClipboardImagePaste'

const mounted: Array<{ unmount: () => void }> = []

function mountWithPaste(onImage: (file: File) => void, enabled: () => boolean = () => true) {
  const wrapper = mount(
    defineComponent({
      setup() {
        useClipboardImagePaste(onImage, enabled)
        return () => h('div')
      },
    }),
  )
  mounted.push(wrapper)
  return wrapper
}

function paste(files: File[]) {
  const event = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', { value: { files } })
  document.body.dispatchEvent(event)
  return event
}

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
})

describe('useClipboardImagePaste', () => {
  it('hands a pasted image over under a name that matches its type', () => {
    const onImage = vi.fn()
    mountWithPaste(onImage)

    const event = paste([new File(['image'], 'image', { type: 'image/jpeg' })])

    expect(event.defaultPrevented).toBe(true)
    const file = onImage.mock.calls[0]?.[0] as File
    expect(file.name).toBe('pasted-image.jpg')
    expect(file.type).toBe('image/jpeg')
  })

  it('passes an unknown image type through unchanged so the caller can reject it', () => {
    const onImage = vi.fn()
    mountWithPaste(onImage)
    const svg = new File(['<svg/>'], 'drawing.svg', { type: 'image/svg+xml' })

    paste([svg])

    expect(onImage).toHaveBeenCalledWith(svg)
  })

  it('leaves pastes without an image to the browser', () => {
    const onImage = vi.fn()
    mountWithPaste(onImage)

    const textPaste = paste([])
    const pdfPaste = paste([new File(['pdf'], 'doc.pdf', { type: 'application/pdf' })])

    expect(onImage).not.toHaveBeenCalled()
    expect(textPaste.defaultPrevented).toBe(false)
    expect(pdfPaste.defaultPrevented).toBe(false)
  })

  it('ignores pastes while disabled and after unmount', () => {
    const onImage = vi.fn()
    const wrapper = mountWithPaste(onImage, () => false)
    const image = new File(['image'], 'a.png', { type: 'image/png' })

    expect(paste([image]).defaultPrevented).toBe(false)

    wrapper.unmount()
    paste([image])

    expect(onImage).not.toHaveBeenCalled()
  })
})
