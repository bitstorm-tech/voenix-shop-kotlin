/**
 * A mug swatch shows the mug from above: the outside colour as the disc, the inside colour as the
 * opening in its middle. A mug that is one colour inside and out is a plain disc.
 */
export function mugSwatchColor(variant: {
  outsideColorCode: string
  insideColorCode: string
}): string {
  const { outsideColorCode: outside, insideColorCode: inside } = variant
  if (outside.toLowerCase() === inside.toLowerCase()) return outside

  return `radial-gradient(circle, ${inside} 0 40%, ${outside} calc(40% + 0.5px))`
}
