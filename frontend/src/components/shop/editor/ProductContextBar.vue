<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Coffee, Shirt } from 'lucide-vue-next'
import { formatPrice } from '@/lib/formatPrice'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import type { EditorArticle, EditorArticleVariant } from './types'

const props = defineProps<{
  article: EditorArticle
  variant: EditorArticleVariant
}>()

const { t } = useI18n()
const imageLoadFailed = shallowRef(false)

const price = computed(() => formatPrice(props.article.price))
const variantImageUrl = computed(() =>
  props.variant.exampleImageFilename
    ? variantExampleImageUrl(props.article.type, props.variant.exampleImageFilename, 200)
    : null,
)
const shouldShowVariantImage = computed(
  () => variantImageUrl.value !== null && !imageLoadFailed.value,
)
/** The fallback while a photo is missing: the shape of the thing, per article type. */
const placeholderIcon = computed(() => (props.article.type === 'TSHIRT' ? Shirt : Coffee))

/**
 * A mug is described in millimetres, a shirt only by the ratio its design is printed in - so the
 * line says whichever of the two the article really knows.
 */
const printAreaLabel = computed(() => {
  const article = props.article
  if (article.type === 'TSHIRT') {
    return t('editor.context.printRatio', { ratio: article.printAspectRatio })
  }

  const printArea = article.printArea
  if (!printArea || printArea.documentFormatWidthMm === null)
    return t('editor.context.printAreaUnknown')

  return t('editor.context.printArea', {
    width: printArea.documentFormatWidthMm,
    height: printArea.documentFormatHeightMm,
  })
})

watch(variantImageUrl, () => {
  imageLoadFailed.value = false
})
</script>

<template>
  <section class="product-context rounded-lg" data-testid="editor-product-context">
    <div class="product-context-media rounded-md">
      <img
        v-if="shouldShowVariantImage && variantImageUrl"
        :src="variantImageUrl"
        :alt="`${article.name} ${variant.name}`"
        class="product-context-image"
        @error="imageLoadFailed = true"
      />
      <div v-else class="product-context-placeholder" aria-hidden="true">
        <component :is="placeholderIcon" class="size-5" />
      </div>
    </div>

    <div class="product-context-main">
      <h1 class="product-context-title">{{ article.name }}</h1>
      <p class="product-context-detail">{{ variant.name }} - {{ price }} - {{ printAreaLabel }}</p>
    </div>
  </section>
</template>

<style scoped>
.product-context {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 0.875rem;
  padding: 0.875rem 1rem;
  border: 1px solid oklch(0.9 0.012 80 / 0.8);
  background: oklch(0.985 0.004 80 / 0.86);
  box-shadow: inset 0 1px 0 oklch(1 0 0 / 0.7);
}

.product-context-media {
  display: grid;
  width: 3.5rem;
  height: 3.5rem;
  place-items: center;
  overflow: hidden;
  border: 1px solid oklch(0.88 0.014 80 / 0.9);
  background: oklch(0.98 0.01 80);
  color: oklch(0.48 0.15 35);
}

.product-context-image {
  width: 100%;
  height: 100%;
  padding: 0.2rem;
  object-fit: contain;
}

.product-context-placeholder {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  background: oklch(0.95 0.025 55);
}

.product-context-main {
  min-width: 0;
}

.product-context-title {
  margin: 0;
  overflow: hidden;
  color: var(--foreground);
  font-size: 1rem;
  font-weight: 750;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-context-detail {
  margin: 0.2rem 0 0;
  color: var(--muted-foreground);
  font-size: 0.84rem;
  line-height: 1.35;
}

.dark .product-context {
  border-color: oklch(0.58 0.026 285 / 0.46);
  background: linear-gradient(
    165deg,
    oklch(0.3 0.012 285 / 0.82) 0%,
    oklch(0.22 0.01 285 / 0.9) 100%
  );
  box-shadow: inset 0 1px 0 oklch(1 0 0 / 0.05);
}

.dark .product-context-media {
  border-color: oklch(1 0 0 / 0.14);
  background: oklch(0.24 0.01 285 / 0.88);
  color: oklch(0.82 0.13 55);
}

.dark .product-context-placeholder {
  background: oklch(0.33 0.055 35 / 0.52);
}

@media (max-width: 560px) {
  .product-context-title {
    white-space: normal;
  }
}
</style>
