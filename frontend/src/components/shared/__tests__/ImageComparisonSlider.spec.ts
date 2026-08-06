import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ImageComparisonSlider from '@/components/shared/ImageComparisonSlider.vue'
import { Slider } from '@/components/ui/slider'

function mountSlider() {
  return mount(ImageComparisonSlider, {
    props: {
      beforeImage: '/before.png',
      afterImage: '/after.png',
      beforeAlt: 'Before',
      afterAlt: 'After',
      beforeLabel: 'Before',
      afterLabel: 'After',
      hintLabel: 'Slide to compare',
      sliderAriaLabel: 'Comparison position',
      initialPosition: 54,
    },
  })
}

describe('ImageComparisonSlider', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class ResizeObserver {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses the shared slider primitive instead of a native range input', async () => {
    const wrapper = mountSlider()

    expect(wrapper.findComponent(Slider).exists()).toBe(true)
    expect(wrapper.find('input[type="range"]').exists()).toBe(false)

    wrapper.findComponent(Slider).vm.$emit('update:modelValue', [25])
    await nextTick()

    expect(wrapper.get('.comparison-before').attributes('style')).toContain('inset(0 75% 0 0)')
    expect(wrapper.get('.comparison-divider').attributes('style')).toContain('left: 25%')
  })
})
