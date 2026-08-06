import { describe, expect, it } from 'vitest'
import {
  composePhoneNumber,
  createDialCodeOptions,
  getDefaultDialCode,
  getDialCode,
  getPhoneNumberPart,
} from '../phoneNumber'

describe('phoneNumber helpers', () => {
  const countries = [
    { name: 'Germany', countryCode: 'DE', dialCode: '+49' },
    { name: 'Austria', countryCode: 'AT', dialCode: '+43' },
    { name: 'Netherlands', countryCode: 'NL', dialCode: '+31' },
  ]
  const dialCodeOptions = createDialCodeOptions(countries)

  it('composes a German local mobile number as an international number', () => {
    expect(composePhoneNumber('+49', '017623123456')).toBe('+4917623123456')
  })

  it('removes common visual separators', () => {
    expect(composePhoneNumber('+49', '0176 / 231-23456')).toBe('+4917623123456')
  })

  it('keeps an already international number', () => {
    expect(composePhoneNumber('+49', '+31612345678')).toBe('+31612345678')
  })

  it('keeps pasted international digits from duplicating the selected prefix', () => {
    expect(composePhoneNumber('+49', '4917623123456')).toBe('+4917623123456')
    expect(composePhoneNumber('+49', '004917623123456')).toBe('+4917623123456')
  })

  it('gets the default dial code from the selected country', () => {
    expect(getDefaultDialCode('AT', dialCodeOptions)).toBe('+43')
  })

  it('splits a stored phone number into prefix and number part', () => {
    expect(getDialCode('+4917623123456', 'DE', dialCodeOptions)).toBe('+49')
    expect(getPhoneNumberPart('+4917623123456', dialCodeOptions)).toBe('17623123456')
  })

  it('handles missing phone values as empty input', () => {
    expect(getDialCode(null, 'AT', dialCodeOptions)).toBe('+43')
    expect(getPhoneNumberPart(undefined, dialCodeOptions)).toBe('')
    expect(composePhoneNumber('+49', null)).toBe('')
  })

  it('creates dial code options from backend countries', () => {
    expect(
      createDialCodeOptions([
        { name: 'France', countryCode: 'fr', dialCode: '+33' },
        { name: 'Unknown', countryCode: 'XX', dialCode: null },
      ]),
    ).toEqual([
      expect.objectContaining({
        countryCode: 'FR',
        label: '+33',
        dialCode: '+33',
      }),
    ])
  })
})
