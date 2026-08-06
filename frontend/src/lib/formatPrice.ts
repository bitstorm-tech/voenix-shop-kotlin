const eurFormatter = new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' })

export function formatPrice(priceInCents: number): string {
  return eurFormatter.format(priceInCents / 100)
}
