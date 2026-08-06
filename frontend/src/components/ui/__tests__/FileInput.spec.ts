import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { FileInput } from '@/components/ui/file-input'

describe('FileInput', () => {
  it('emits selected files without exposing a raw file input contract to consumers', async () => {
    const wrapper = mount(FileInput, {
      props: {
        inputTestId: 'asset-picker',
        multiple: true,
      },
    })
    const files = [
      new File(['first'], 'first.png', { type: 'image/png' }),
      new File(['second'], 'second.png', { type: 'image/png' }),
    ]
    const input = wrapper.get('[data-testid="asset-picker"]')

    Object.defineProperty(input.element, 'files', {
      value: files,
      configurable: true,
    })

    await input.trigger('change')

    expect(wrapper.emitted('update:modelValue')).toEqual([[files]])
    expect(wrapper.emitted('change')).toEqual([[files]])
  })

  it('exposes an imperative clear method for composed upload flows', async () => {
    const wrapper = mount(FileInput, {
      props: {
        defaultValue: [new File(['image'], 'image.png', { type: 'image/png' })],
      },
    })

    wrapper.vm.clear()
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('update:modelValue')).toEqual([[[]]])
    expect(wrapper.emitted('change')).toEqual([[[]]])
  })
})
