import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ColorInput } from '@/components/ui/color-input'

describe('ColorInput', () => {
  it('emits color value updates from the visible picker', async () => {
    const wrapper = mount(ColorInput, {
      props: {
        defaultValue: '#000000',
      },
    })
    const input = wrapper.get('input')

    await input.setValue('#ffffff')

    expect(wrapper.emitted('update:modelValue')).toEqual([['#ffffff']])
    expect(wrapper.emitted('change')).toEqual([['#ffffff']])
  })

  it('can visually hide the native picker behind a swatch trigger', async () => {
    const wrapper = mount(ColorInput, {
      props: {
        modelValue: '#ff0000',
        visuallyHidden: true,
      },
    })
    const input = wrapper.get('input')
    const click = vi.spyOn(input.element as HTMLInputElement, 'click').mockImplementation(() => {})

    expect(input.classes()).toContain('sr-only')

    await wrapper.get('button').trigger('click')

    expect(click).toHaveBeenCalledOnce()
  })
})
