<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ModelViewerElement } from '@google/model-viewer'
import { Eye, ImageIcon, Loader2, Rotate3D } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogDescription,
  DialogHeader,
  DialogContent,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { SegmentedControl, SegmentedControlItem } from '@/components/ui/segmented-control'
import type { CartItem } from '@/stores/shop/cart'
import { useMugsStore } from '@/stores/shop/mugs'
import { formatPrice } from '@/lib/formatPrice'
import mugModel from '@/assets/3d/mug.glb?url'

const props = defineProps<{
  item: CartItem
}>()

const { t } = useI18n()
const mugsStore = useMugsStore()

type PreviewMode = 'mug' | 'print'

const open = shallowRef(false)
const mode = shallowRef<PreviewMode>('mug')
const modelViewerRef = shallowRef<HTMLElement | null>(null)
const modelViewerLoaded = shallowRef(false)
const modelReady = shallowRef(false)
let textureGeneration = 0

/** A line whose article the catalog no longer answers for carries no name (`available = false`). */
const articleName = computed(() => props.item.articleName ?? t('cart.unknownArticle'))
const hasDesignImage = computed(() => props.item.imageId !== null)
const designImageUrl = computed(() =>
  props.item.imageId ? `/api/images/guest/1600/${props.item.imageId}` : '',
)
const itemTotal = computed(() =>
  formatPrice((props.item.price + props.item.promptPrice) * props.item.quantity),
)
const selectedVariant = computed(() => {
  const mug = mugsStore.getMugById(props.item.articleId)
  return mug?.variants.find((variant) => variant.id === props.item.variantId) ?? null
})
const variantImageUrl = computed(() =>
  selectedVariant.value?.exampleImageFilename
    ? `/api/images/public/200/articles/mugs/variant-example-images/${selectedVariant.value.exampleImageFilename}`
    : null,
)
const variantStyle = computed(() => ({
  backgroundColor: props.item.outsideColorCode ?? 'transparent',
  boxShadow: props.item.insideColorCode
    ? `inset 0 -18px 26px -10px ${props.item.insideColorCode}`
    : undefined,
}))

watch(open, async (isOpen) => {
  if (!isOpen) {
    modelReady.value = false
    return
  }

  mode.value = 'mug'
  void mugsStore.fetchMugs()
  await prepareMugPreview()
})

watch(mode, async (newMode) => {
  if (open.value && newMode === 'mug') {
    await prepareMugPreview()
  }
})

onBeforeUnmount(() => {
  textureGeneration++
})

async function prepareMugPreview() {
  if (!hasDesignImage.value) return

  if (!modelViewerLoaded.value) {
    await import('@google/model-viewer')
    modelViewerLoaded.value = true
  }

  await nextTick()
  await applyTextureWhenReady()
}

async function applyTextureWhenReady() {
  const mv = modelViewerRef.value as ModelViewerElement | null
  if (!mv || !hasDesignImage.value) return

  const generation = ++textureGeneration

  if (!mv.model) {
    mv.addEventListener('load', () => applyTextureWhenReady(), { once: true })
    return
  }

  try {
    const dataUrl = await loadImageDataUrl(designImageUrl.value)
    if (generation !== textureGeneration) return

    const texture = await mv.createTexture(dataUrl)
    const material = mv.model.materials.find((m) => m.name === 'PrintArea') ?? mv.model.materials[0]
    material?.pbrMetallicRoughness?.baseColorTexture?.setTexture(texture)
    modelReady.value = true
  } catch (error) {
    console.error('Failed to render cart item preview:', error)
    modelReady.value = true
  }
}

function loadImageDataUrl(url: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      const canvas = document.createElement('canvas')
      canvas.width = img.naturalWidth
      canvas.height = img.naturalHeight

      const ctx = canvas.getContext('2d')
      if (!ctx) {
        reject(new Error('Canvas context unavailable'))
        return
      }

      ctx.drawImage(img, 0, 0)
      resolve(canvas.toDataURL('image/png'))
    }
    img.onerror = reject
    img.src = url
  })
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogTrigger as-child>
      <Button variant="outline" size="sm" class="h-8 px-2.5 text-xs" :disabled="!hasDesignImage">
        <Eye class="size-3.5" />
        {{ t('cart.preview.open') }}
      </Button>
    </DialogTrigger>

    <DialogContent class="max-w-5xl gap-0 overflow-x-hidden p-0">
      <DialogHeader class="border-b border-border px-5 py-4 sm:px-6">
        <DialogTitle class="pr-8 text-lg">{{ t('cart.preview.title') }}</DialogTitle>
        <DialogDescription class="sr-only">
          {{ t('cart.preview.description') }}
        </DialogDescription>
      </DialogHeader>

      <div class="grid gap-0 lg:grid-cols-[minmax(0,1fr)_280px]">
        <div class="min-h-[420px] bg-muted/30 p-4 sm:p-6">
          <SegmentedControl v-model="mode" type="single" class="mb-4">
            <SegmentedControlItem
              value="mug"
              data-testid="cart-preview-mug-mode"
              class="min-h-8 px-3 text-[13px]"
            >
              <Rotate3D class="size-4" />
              {{ t('cart.preview.mugView') }}
            </SegmentedControlItem>
            <SegmentedControlItem
              value="print"
              data-testid="cart-preview-print-mode"
              class="min-h-8 px-3 text-[13px]"
            >
              <ImageIcon class="size-4" />
              {{ t('cart.preview.printView') }}
            </SegmentedControlItem>
          </SegmentedControl>

          <div
            class="preview-stage relative flex h-[min(62vh,560px)] min-h-80 items-center justify-center overflow-hidden rounded-lg border border-border p-[clamp(16px,3vw,32px)]"
          >
            <template v-if="mode === 'mug'">
              <div v-if="!modelReady" class="absolute inset-0 flex items-center justify-center">
                <Loader2 class="size-8 animate-spin text-muted-foreground" />
              </div>
              <model-viewer
                v-if="modelViewerLoaded"
                ref="modelViewerRef"
                :src="mugModel"
                :alt="t('cart.preview.mugAlt')"
                camera-controls
                auto-rotate
                camera-orbit="0deg 75deg auto"
                min-camera-orbit="auto auto 200%"
                max-camera-orbit="auto auto 250%"
                shadow-intensity="1"
                environment-image="neutral"
                class="h-full w-full transition-opacity duration-300"
                :class="modelReady ? 'opacity-100' : 'opacity-0'"
                @load="applyTextureWhenReady"
              />
            </template>

            <img
              v-else
              :src="designImageUrl"
              :alt="t('cart.preview.printAlt')"
              class="max-h-full max-w-full object-contain"
            />
          </div>
        </div>

        <aside class="border-t border-border bg-background p-5 lg:border-l lg:border-t-0">
          <div class="flex items-start gap-3">
            <img
              v-if="variantImageUrl"
              :src="variantImageUrl"
              :alt="articleName"
              class="size-14 shrink-0 rounded-md border border-border bg-muted/40 object-contain p-1"
            />
            <div
              v-else
              class="size-14 shrink-0 rounded-md border border-border"
              :style="variantStyle"
            />
            <div class="min-w-0">
              <h3 class="font-medium leading-tight">{{ articleName }}</h3>
              <p v-if="item.variantName" class="mt-1 text-sm text-muted-foreground">
                {{ t('cart.variant') }}: {{ item.variantName }}
              </p>
            </div>
          </div>

          <dl class="mt-6 space-y-3 text-sm">
            <div class="flex justify-between gap-4">
              <dt class="text-muted-foreground">{{ t('cart.preview.quantity') }}</dt>
              <dd class="font-medium tabular-nums">{{ item.quantity }}</dd>
            </div>
            <div class="flex justify-between gap-4">
              <dt class="text-muted-foreground">{{ t('cart.preview.total') }}</dt>
              <dd class="font-semibold tabular-nums">{{ itemTotal }}</dd>
            </div>
          </dl>
        </aside>
      </div>
    </DialogContent>
  </Dialog>
</template>

<style scoped>
/* Keep the layered stage background in CSS; the equivalent arbitrary utility is harder to scan. */
.preview-stage {
  background:
    linear-gradient(135deg, oklch(1 0 0 / 0.7), oklch(0.96 0.006 80 / 0.8)),
    radial-gradient(circle at 50% 20%, oklch(1 0 0), transparent 48%);
}
</style>
