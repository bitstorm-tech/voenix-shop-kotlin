import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { CheckboxCard } from '@/components/ui/checkbox-card'

describe('CheckboxCard', () => {
  it('wraps checkbox behavior in a selectable option row', async () => {
    const wrapper = mount(CheckboxCard, {
      slots: {
        default: 'Receive updates',
      },
    })
    const input = wrapper.get('input')

    await input.setValue(true)

    expect(wrapper.emitted('update:modelValue')).toEqual([[true]])
    expect(wrapper.emitted('change')).toEqual([[true]])
    expect(wrapper.text()).toContain('Receive updates')
  })

  it('marks disabled option rows as unavailable', () => {
    const wrapper = mount(CheckboxCard, {
      props: {
        disabled: true,
      },
    })

    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    expect(wrapper.classes()).toContain('cursor-not-allowed')
  })
})
