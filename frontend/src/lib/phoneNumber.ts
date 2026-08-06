export interface DialCodeOption {
  countryCode: string
  flag: string
  label: string
  dialCode: string
}

export interface DialCodeCountry {
  name: string
  countryCode: string
  dialCode: string | null
}

const fallbackDialCode = '+49'
const regionalIndicatorOffset = 127397

export function createDialCodeOptions(countries: DialCodeCountry[]): DialCodeOption[] {
  return countries.flatMap((country) => {
    const countryCode = country.countryCode.trim().toUpperCase()
    const dialCode = country.dialCode?.trim()

    if (!/^[A-Z]{2}$/.test(countryCode) || !dialCode || !/^\+\d+$/.test(dialCode)) {
      return []
    }

    return [
      {
        countryCode,
        flag: toFlagEmoji(countryCode),
        label: dialCode,
        dialCode,
      },
    ]
  })
}

export function getDefaultDialCode(
  country: string | null | undefined,
  dialCodeOptions: DialCodeOption[],
): string {
  const normalizedCountry = country?.trim().toUpperCase()
  return (
    dialCodeOptions.find((option) => option.countryCode === normalizedCountry)?.dialCode ??
    dialCodeOptions[0]?.dialCode ??
    fallbackDialCode
  )
}

export function getDialCode(
  phone: string | null | undefined,
  country: string | null | undefined,
  dialCodeOptions: DialCodeOption[],
): string {
  const normalized = phone?.trim() ?? ''
  const matchingOption = findMatchingDialCodeOption(normalized, dialCodeOptions)
  return matchingOption?.dialCode ?? getDefaultDialCode(country, dialCodeOptions)
}

export function getPhoneNumberPart(
  phone: string | null | undefined,
  dialCodeOptions: DialCodeOption[],
): string {
  const normalized = phone?.trim() ?? ''
  const matchingOption = findMatchingDialCodeOption(normalized, dialCodeOptions)

  if (matchingOption) {
    return normalized.slice(matchingOption.dialCode.length)
  }

  return normalized
}

export function composePhoneNumber(
  dialCode: string | null | undefined,
  number: string | null | undefined,
): string {
  const normalizedDialCode = dialCode || fallbackDialCode
  const normalizedNumber = number?.replace(/[\s\-/.()]/g, '') ?? ''
  const dialCodeDigits = normalizedDialCode.slice(1)

  if (!normalizedNumber) {
    return ''
  }

  if (normalizedNumber.startsWith('+')) {
    return normalizedNumber
  }

  if (normalizedNumber.startsWith('00')) {
    return `+${normalizedNumber.slice(2)}`
  }

  if (normalizedNumber.startsWith(dialCodeDigits)) {
    return `${normalizedDialCode}${normalizedNumber.slice(dialCodeDigits.length)}`
  }

  return `${normalizedDialCode}${normalizedNumber.replace(/^0+/, '')}`
}

export function hasExplicitDialCode(phone: string | null | undefined): boolean {
  return phone?.trim().startsWith('+') ?? false
}

function findMatchingDialCodeOption(
  phone: string,
  dialCodeOptions: DialCodeOption[],
): DialCodeOption | undefined {
  return [...dialCodeOptions]
    .sort((first, second) => second.dialCode.length - first.dialCode.length)
    .find((option) => phone.startsWith(option.dialCode))
}

function toFlagEmoji(countryCode: string): string {
  return Array.from(countryCode)
    .map((letter) => String.fromCodePoint(letter.charCodeAt(0) + regionalIndicatorOffset))
    .join('')
}
