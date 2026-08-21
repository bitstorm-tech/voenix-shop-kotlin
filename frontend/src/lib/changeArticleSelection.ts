import type { EditorArticle } from '@/components/shop/editor/types'

const ASPECT_RATIO_EPSILON = 0.0001

/** The little a variant resolver needs to know about a variant. */
interface VariantLike {
  id: number
  isDefault: boolean
}

/** The little a variant resolver needs to know about an article. */
interface ArticleLike {
  id: number
  variants: VariantLike[]
}

export interface ArticleChangeDecision {
  selectedArticleId: number
  selectedVariantId: number | null
  isArticleChange: boolean
  isVariantOnlyChange: boolean
  shouldResetCropTransforms: boolean
}

interface DecideArticleChangeSelectionInput {
  currentArticle: EditorArticle | null
  currentArticleId: number | null
  currentVariantId: number | null
  nextArticle: EditorArticle
  requestedVariantId?: number | null
}

/**
 * The two variant resolvers work on every article type, because "the default one, otherwise the
 * first one" is a rule about the variant list and not about what the article is made of. They are
 * generic over the article so a mug in answers a mug variant out, and a shirt a shirt variant.
 */
export function resolveDefaultVariant<A extends ArticleLike>(
  article: A,
): A['variants'][number] | null {
  return article.variants.find((variant) => variant.isDefault) ?? article.variants[0] ?? null
}

export function resolveDisplayVariant<A extends ArticleLike>(
  article: A,
  selectedArticleId: number | null,
  selectedVariantId: number | null,
): A['variants'][number] | null {
  if (article.id === selectedArticleId && selectedVariantId !== null) {
    const selectedVariant = article.variants.find(
      (variant: VariantLike) => variant.id === selectedVariantId,
    )
    if (selectedVariant) return selectedVariant
  }

  return resolveDefaultVariant(article)
}

/**
 * The ratio the print frame is drawn in. A mug derives it from its document format, a shirt from
 * its print aspect ratio - by the time an article reaches the editor both are the same one number.
 */
export function getPrintAspectRatio(article: EditorArticle | null): number | null {
  return article?.printArea?.aspectRatio ?? null
}

export function hasDifferentPrintAspectRatio(
  currentArticle: EditorArticle | null,
  nextArticle: EditorArticle,
): boolean {
  const currentAspectRatio = getPrintAspectRatio(currentArticle)
  const nextAspectRatio = getPrintAspectRatio(nextArticle)

  if (currentAspectRatio === null || nextAspectRatio === null) {
    return currentAspectRatio !== nextAspectRatio
  }

  return Math.abs(currentAspectRatio - nextAspectRatio) > ASPECT_RATIO_EPSILON
}

/**
 * What changes when the customer picks another article or another variant of the same one.
 *
 * The crop transforms are kept as long as the print frame keeps its shape: a different colour or a
 * different shirt size prints into the same rectangle, so the crop the customer set stays right. A
 * different print ratio would show the crop somewhere else than it was set, so it is reset.
 */
export function decideArticleChangeSelection({
  currentArticle,
  currentArticleId,
  currentVariantId,
  nextArticle,
  requestedVariantId,
}: DecideArticleChangeSelectionInput): ArticleChangeDecision {
  const isArticleChange = currentArticleId !== nextArticle.id
  const requestedVariant =
    requestedVariantId == null
      ? null
      : (nextArticle.variants.find((variant) => variant.id === requestedVariantId) ?? null)
  const currentVariant =
    currentVariantId == null
      ? null
      : (nextArticle.variants.find((variant) => variant.id === currentVariantId) ?? null)
  const selectedVariant =
    requestedVariant ??
    (!isArticleChange ? currentVariant : null) ??
    resolveDefaultVariant(nextArticle)
  const selectedVariantId = selectedVariant?.id ?? null

  return {
    selectedArticleId: nextArticle.id,
    selectedVariantId,
    isArticleChange,
    isVariantOnlyChange: !isArticleChange && selectedVariantId !== currentVariantId,
    shouldResetCropTransforms:
      isArticleChange && hasDifferentPrintAspectRatio(currentArticle, nextArticle),
  }
}
