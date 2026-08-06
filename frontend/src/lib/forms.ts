export function optionalText(value: string): string | null {
  const trimmedValue = value.trim()
  return trimmedValue === '' ? null : trimmedValue
}
