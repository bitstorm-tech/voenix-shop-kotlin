import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { PasswordInput } from '@/components/ui/password-input'

describe('PasswordInput', () => {
  it('starts masked with a labelled, unpressed toggle button', () => {
    const wrapper = mount(PasswordInput)
    const input = wrapper.get('input')
    const button = wrapper.get('button')

    expect(input.attributes('type')).toBe('password')
    expect(button.attributes('type')).toBe('button')
    expect(button.attributes('aria-pressed')).toBe('false')
    expect(button.attributes('aria-label')).toBe('Show password')
  })

  it('toggles the type of the same input element without losing its value', async () => {
    const wrapper = mount(PasswordInput, { props: { modelValue: 'secret' } })
    const button = wrapper.get('button')
    const elementBefore = wrapper.get('input').element

    await button.trigger('click')

    expect(wrapper.get('input').attributes('type')).toBe('text')
    expect(button.attributes('aria-pressed')).toBe('true')

    await button.trigger('click')

    const elementAfter = wrapper.get('input').element
    expect(wrapper.get('input').attributes('type')).toBe('password')
    expect(button.attributes('aria-pressed')).toBe('false')
    expect(elementAfter).toBe(elementBefore)
    expect(elementAfter.value).toBe('secret')
  })

  it('forwards attributes to the input and merges the class prop', () => {
    const wrapper = mount(PasswordInput, {
      props: { class: 'custom-class' },
      attrs: {
        id: 'password',
        autocomplete: 'new-password',
        required: true,
        minlength: '8',
        'aria-invalid': true,
        'data-testid': 'password-field',
      },
    })
    const input = wrapper.get('input')

    expect(input.attributes('id')).toBe('password')
    expect(input.attributes('autocomplete')).toBe('new-password')
    expect(input.attributes('required')).toBeDefined()
    expect(input.attributes('minlength')).toBe('8')
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(input.attributes('data-testid')).toBe('password-field')
    expect(input.classes()).toContain('pr-10')
    expect(input.classes()).toContain('custom-class')
  })

  it('does not submit the surrounding form when the toggle is clicked', async () => {
    const onSubmit = vi.fn()
    const wrapper = mount({
      components: { PasswordInput },
      setup: () => ({ onSubmit }),
      template: '<form @submit.prevent="onSubmit"><PasswordInput /></form>',
    })

    await wrapper.get('button').trigger('click')

    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('prevents the default of pointerdown so the input keeps focus', async () => {
    const wrapper = mount(PasswordInput)
    const event = new Event('pointerdown', { bubbles: true, cancelable: true })

    wrapper.get('button').element.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(true)
  })

  it('uses a custom label as the accessible name', () => {
    const wrapper = mount(PasswordInput, { props: { label: 'Show access token' } })

    expect(wrapper.get('button').attributes('aria-label')).toBe('Show access token')
  })

  it('toggles two instances independently', async () => {
    const wrapper = mount({
      components: { PasswordInput },
      template: '<div><PasswordInput /><PasswordInput /></div>',
    })
    const buttons = wrapper.findAll('button')

    await buttons[0]!.trigger('click')

    const inputs = wrapper.findAll('input')
    expect(inputs[0]!.attributes('type')).toBe('text')
    expect(inputs[1]!.attributes('type')).toBe('password')
    expect(buttons[1]!.attributes('aria-pressed')).toBe('false')
  })
})
