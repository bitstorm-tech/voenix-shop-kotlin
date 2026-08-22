/**
 * The two variant resolvers work on every article type, because "the default one, otherwise the
 * first one" is a rule about the variant list and not about what the article is made of. They are
 * generic over the article so a mug in answers a mug variant out, and a shirt a shirt variant.
 */
export function resolveDefaultVariant<
  A extends { id: number; variants: { id: number; isDefault: boolean }[] },
>(article: A): A['variants'][number] | null {
  return article.variants.find((variant) => variant.isDefault) ?? article.variants[0] ?? null
}

export function resolveDisplayVariant<
  A extends { id: number; variants: { id: number; isDefault: boolean }[] },
>(
  article: A,
  selectedArticleId: number | null,
  selectedVariantId: number | null,
): A['variants'][number] | null {
  if (article.id === selectedArticleId && selectedVariantId !== null) {
    const selectedVariant = article.variants.find((variant) => variant.id === selectedVariantId)
    if (selectedVariant) return selectedVariant
  }

  return resolveDefaultVariant(article)
}
