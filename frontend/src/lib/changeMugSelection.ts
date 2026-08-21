import type { MugDto, MugVariantDto, ShopArticle, ShopArticleVariant } from '@/stores/shop/catalog'

const ASPECT_RATIO_EPSILON = 0.0001

export interface MugChangeDecision {
  selectedMugId: number
  selectedVariantId: number | null
  isMugChange: boolean
  isVariantOnlyChange: boolean
  shouldResetCropTransforms: boolean
}

interface DecideMugChangeSelectionInput {
  currentMug: MugDto | null
  currentMugId: number | null
  currentVariantId: number | null
  nextMug: MugDto
  requestedVariantId?: number | null
}

/**
 * The two variant resolvers work on every article type, because "the default one, otherwise the
 * first one" is a rule about the variant list and not about what the article is made of. They are
 * generic over the article so a mug in still answers a `MugVariantDto` out.
 */
export function resolveDefaultVariant<A extends ShopArticle>(
  article: A,
): A['variants'][number] | null {
  return article.variants.find((variant) => variant.isDefault) ?? article.variants[0] ?? null
}

export function resolveDisplayVariant<A extends ShopArticle>(
  article: A,
  selectedArticleId: number | null,
  selectedVariantId: number | null,
): A['variants'][number] | null {
  if (article.id === selectedArticleId && selectedVariantId !== null) {
    const selectedVariant = article.variants.find(
      (variant: ShopArticleVariant) => variant.id === selectedVariantId,
    )
    if (selectedVariant) return selectedVariant
  }

  return resolveDefaultVariant(article)
}

export function getDocumentFormatAspectRatio(mug: MugDto | null): number | null {
  const width = mug?.mugDetails.documentFormatWidthMm
  const height = mug?.mugDetails.documentFormatHeightMm

  if (width == null || height == null || width <= 0 || height <= 0) return null

  return width / height
}

export function hasDifferentDocumentFormatAspectRatio(
  currentMug: MugDto | null,
  nextMug: MugDto,
): boolean {
  const currentAspectRatio = getDocumentFormatAspectRatio(currentMug)
  const nextAspectRatio = getDocumentFormatAspectRatio(nextMug)

  if (currentAspectRatio === null || nextAspectRatio === null) {
    return currentAspectRatio !== nextAspectRatio
  }

  return Math.abs(currentAspectRatio - nextAspectRatio) > ASPECT_RATIO_EPSILON
}

export function decideMugChangeSelection({
  currentMug,
  currentMugId,
  currentVariantId,
  nextMug,
  requestedVariantId,
}: DecideMugChangeSelectionInput): MugChangeDecision {
  const isMugChange = currentMugId !== nextMug.id
  const requestedVariant =
    requestedVariantId == null
      ? null
      : (nextMug.variants.find((variant) => variant.id === requestedVariantId) ?? null)
  const currentVariant =
    currentVariantId == null
      ? null
      : (nextMug.variants.find((variant) => variant.id === currentVariantId) ?? null)
  const selectedVariant =
    requestedVariant ?? (!isMugChange ? currentVariant : null) ?? resolveDefaultVariant(nextMug)
  const selectedVariantId = selectedVariant?.id ?? null

  return {
    selectedMugId: nextMug.id,
    selectedVariantId,
    isMugChange,
    isVariantOnlyChange: !isMugChange && selectedVariantId !== currentVariantId,
    shouldResetCropTransforms:
      isMugChange && hasDifferentDocumentFormatAspectRatio(currentMug, nextMug),
  }
}
