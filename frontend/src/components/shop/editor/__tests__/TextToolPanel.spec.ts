import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TextToolPanel from '@/components/shop/editor/TextToolPanel.vue'
import { ColorInput } from '@/components/ui/color-input'
import { Label } from '@/components/ui/label'
import { SwatchButton } from '@/components/ui/swatch-button'
import type { TextOverlay } from '@/stores/shop/textOverlays'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

function overlay(patch: Partial<TextOverlay> = {}): TextOverlay {
  return {
    id: 'overlay-1',
    text: 'Hello',
    rx: 0.5,
    ry: 0.5,
    fontFamily: 'Plus Jakarta Sans',
    fontSize: 64,
    color: 'oklch(0.99 0 0)',
    bold: false,
    italic: false,
    underline: false,
    rotation: 0,
    ...patch,
  }
}

function mountTextToolPanel(selected = overlay()) {
  return mount(TextToolPanel, {
    props: {
      overlays: [selected],
      selectedId: selected.id,
    },
    global: {
      stubs: {
        Select: { template: '<div><slot /></div>' },
        SelectTrigger: { template: '<button type="button"><slot /></button>' },
        SelectValue: { template: '<span />' },
        SelectContent: { template: '<div><slot /></div>' },
        SelectItem: { template: '<div><slot /></div>' },
        Slider: { template: '<div><slot /></div>' },
        SliderTrack: { template: '<div><slot /></div>' },
        SliderRange: { template: '<span />' },
        SliderThumb: { template: '<span />' },
      },
    },
  })
}

describe('TextToolPanel', () => {
  afterEach(() => {
    document.querySelectorAll('link[id^="font-"]').forEach((link) => link.remove())
  })

  it('renders text controls through shared label, swatch, and color input primitives', () => {
    const wrapper = mountTextToolPanel()

    expect(wrapper.findAllComponents(Label).map((label) => label.text())).toEqual([
      'editor.textTool.textPlaceholder',
      'editor.textTool.fontFamily',
      'editor.textTool.fontSize',
      'editor.textTool.rotation',
      'editor.textTool.color',
    ])
    expect(wrapper.findAllComponents(SwatchButton)).toHaveLength(10)
    expect(wrapper.findComponent(ColorInput).exists()).toBe(true)
  })

  it('updates the selected overlay when choosing preset and custom colors', async () => {
    const wrapper = mountTextToolPanel()
    const swatches = wrapper.findAllComponents(SwatchButton)

    await swatches[2]!.trigger('click')
    await wrapper.getComponent(ColorInput).get('input').setValue('#123456')

    expect(wrapper.emitted('updateOverlay')).toEqual([
      ['overlay-1', { color: 'oklch(0.61 0.19 35)' }],
      ['overlay-1', { color: '#123456' }],
    ])
  })
})
