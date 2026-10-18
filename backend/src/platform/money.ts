/**
 * Money is integer cents. These two helpers replace the `BigDecimal … HALF_UP` arithmetic of the
 * Kotlin backend without a decimal library.
 */

/**
 * Rounds to the nearest integer; a half rounds away from zero, like `RoundingMode.HALF_UP`
 * (`Math.round` would turn `-2.5` into `-2`).
 *
 * Scale before you divide: a quotient that is an exact half (`1234.5`) is exact in floating point,
 * while a decimal fraction such as `12.345` is not. So compute a two-decimal percentage as
 * `roundHalfUp((amount * 10_000) / base) / 100`, never as `roundHalfUp(amount * 100 / base, 2)`.
 */
export function roundHalfUp(value: number): number {
  const rounded = Math.round(Math.abs(value))
  return rounded === 0 ? 0 : value < 0 ? -rounded : rounded
}

/**
 * `percent` of `cents`, rounded half up to whole cents. `percent` has at most two decimals (a VAT
 * rate such as `19` or `7.5`); it is scaled to hundredths first so the division is exact wherever
 * a half can occur.
 */
export function percentOfCents(cents: number, percent: number): number {
  const percentHundredths = Math.round(percent * 100)
  return roundHalfUp((cents * percentHundredths) / 10_000)
}
