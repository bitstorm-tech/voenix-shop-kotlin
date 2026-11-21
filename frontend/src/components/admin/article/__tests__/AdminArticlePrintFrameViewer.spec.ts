import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminArticlePrintFrameViewer from '../AdminArticlePrintFrameViewer.vue'

describe('AdminArticlePrintFrameViewer', () => {
  it('draws the measured rectangle on the mockup and offers nothing to change it', () => {
    const wrapper = mount(AdminArticlePrintFrameViewer, {
      props: {
        frame: { leftPct: 12.5, topPct: 20, widthPct: 40, heightPct: 22.5 },
        mask: null,
        hotspot: 'front',
        mockupUrl: '/mockup.webp',
      },
    })

    expect(wrapper.get('[data-testid="print-frame-mockup"]').attributes('src')).toBe('/mockup.webp')
    const style = wrapper.get('[data-testid="print-frame-rectangle"]').attributes('style')
    expect(style).toContain('left: 12.5%')
    expect(style).toContain('top: 20%')
    expect(style).toContain('width: 40%')
    expect(style).toContain('height: 22.5%')

    const values = wrapper.get('[data-testid="print-frame-values"]').text()
    expect(values).toContain('front')
    expect(values).toContain('12.5 %')
    expect(values).toContain('22.5 %')

    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.find('[data-testid="print-frame-missing"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="print-frame-masked"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="print-frame-visible"]').attributes('style')).toBeUndefined()
  })

  it('tints only the part of the frame a product that is no rectangle shows', () => {
    const wrapper = mount(AdminArticlePrintFrameViewer, {
      props: {
        frame: { leftPct: 19.65, topPct: 20.75, widthPct: 77.4, heightPct: 77.4 },
        mask: 'data:image/png;base64,AAAA',
        hotspot: 'OVERALL_FRONT',
        mockupUrl: '/mockup.webp',
      },
    })

    const visibleStyle =
      wrapper.get('[data-testid="print-frame-visible"]').attributes('style') ?? ''
    expect(visibleStyle).toContain('url("data:image/png;base64,AAAA")')
    expect(visibleStyle).toContain('mask-repeat: no-repeat')
    expect(wrapper.get('[data-testid="print-frame-masked"]').text()).toContain(
      'Only the tinted part',
    )
  })

  it('explains an unmeasured frame instead of drawing one', () => {
    const wrapper = mount(AdminArticlePrintFrameViewer, {
      props: { frame: null, mask: null, hotspot: null, mockupUrl: '/mockup.webp' },
    })

    expect(wrapper.get('[data-testid="print-frame-missing"]').text()).toContain(
      'The last sync could not measure the print frame; see the sync report. The article cannot be activated.',
    )
    expect(wrapper.find('[data-testid="print-frame-rectangle"]').exists()).toBe(false)
  })
})
