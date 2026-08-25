/**
 * The admin surface is deliberately single-language English, so every timestamp it shows is
 * formatted in one place, in one locale, by one rule.
 *
 * The rule: nothing to show stays nothing (`null`, so each caller picks its own dash or wording),
 * and a value the browser cannot read as a date is shown as it arrived instead of as
 * "Invalid Date" — an operator comparing a screen with a partner backoffice needs the raw string.
 */
const ADMIN_STAMP_FORMAT = new Intl.DateTimeFormat('en', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

export function formatAdminStamp(value: string | null | undefined): string | null {
  if (!value) {
    return null
  }

  const stamp = new Date(value)
  return Number.isNaN(stamp.getTime()) ? value : ADMIN_STAMP_FORMAT.format(stamp)
}
