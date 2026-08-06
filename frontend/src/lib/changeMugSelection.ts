import type { MugDto, MugVariantDto } from '@/stores/shop/mugs'

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

export function resolveDefaultMugVariant(mug: MugDto): MugVariantDto | null {
  return mug.variants.find((variant) => variant.isDefault) ?? mug.variants[0] ?? null
}

export function resolveDisplayMugVariant(
  mug: MugDto,
  selectedMugId: number | null,
  selectedVariantId: number | null,
): MugVariantDto | null {
  if (mug.id === selectedMugId && selectedVariantId !== null) {
    const selectedVariant = mug.variants.find((variant) => variant.id === selectedVariantId)
    if (selectedVariant) return selectedVariant
  }

  return resolveDefaultMugVariant(mug)
}

export function getDocumentFormatAspectRatio(mug: MugDto | null): number | null {
  const width = mug?.mugDetails?.documentFormatWidthMm
  const height = mug?.mugDetails?.documentFormatHeightMm

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
    requestedVariant ?? (!isMugChange ? currentVariant : null) ?? resolveDefaultMugVariant(nextMug)
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
