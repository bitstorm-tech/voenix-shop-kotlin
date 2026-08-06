import { mount } from '@vue/test-utils'
import { shallowRef } from 'vue'
import { describe, expect, it } from 'vitest'
import { SegmentedControl, SegmentedControlItem } from '@/components/ui/segmented-control'
import { SelectableCard } from '@/components/ui/selectable-card'
import { SwatchButton } from '@/components/ui/swatch-button'
import { ThumbnailButton } from '@/components/ui/thumbnail-button'
import { Toggle } from '@/components/ui/toggle'

describe('selection UI primitives', () => {
  it('toggles pressed state through the Toggle wrapper', async () => {
    const wrapper = mount({
      components: {
        Toggle,
      },
      setup() {
        const pressed = shallowRef(false)

        return {
          pressed,
        }
      },
      template: '<Toggle v-model="pressed">Bold</Toggle>',
    })

    await wrapper.get('button').trigger('click')

    expect(wrapper.vm.pressed).toBe(true)
  })

  it('selects a segmented control item', async () => {
    const wrapper = mount({
      components: {
        SegmentedControl,
        SegmentedControlItem,
      },
      setup() {
        const value = shallowRef('grid')

        return {
          value,
        }
      },
      template: `
        <SegmentedControl v-model="value" type="single">
          <SegmentedControlItem value="grid">Grid</SegmentedControlItem>
          <SegmentedControlItem value="list">List</SegmentedControlItem>
        </SegmentedControl>
      `,
    })

    const listButton = wrapper.findAll('button')[1]

    if (!listButton) {
      throw new Error('List segmented control item was not rendered')
    }

    await listButton.trigger('click')

    expect(wrapper.vm.value).toBe('list')
  })

  it('exposes selected state on selectable cards', () => {
    const wrapper = mount(SelectableCard, {
      props: {
        selected: true,
      },
      slots: {
        default: 'A3 poster',
      },
    })

    expect(wrapper.attributes('aria-pressed')).toBe('true')
    expect(wrapper.attributes('data-state')).toBe('selected')
    expect(wrapper.text()).toContain('A3 poster')
  })

  it('renders swatch and thumbnail buttons with selectable state hooks', () => {
    const swatch = mount(SwatchButton, {
      props: {
        color: '#ff0000',
        selected: true,
      },
    })
    const thumbnail = mount(ThumbnailButton, {
      props: {
        alt: 'Generated mug',
        selected: true,
        src: '/mug.png',
      },
    })

    expect(swatch.attributes('aria-pressed')).toBe('true')
    expect(swatch.get('span').attributes('style')).toContain('rgb(255, 0, 0)')
    expect(thumbnail.attributes('data-state')).toBe('selected')
    expect(thumbnail.get('img').attributes('alt')).toBe('Generated mug')
  })
})
