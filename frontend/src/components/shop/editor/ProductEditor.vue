<script setup lang="ts">
import { computed, markRaw, ref, shallowRef, watch, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  Crop,
  Eye,
  Images,
  Loader2,
  PanelLeftClose,
  Pencil,
  RotateCcw,
  ShoppingCart,
  Sticker,
  Type,
} from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { SegmentedControl, SegmentedControlItem } from '@/components/ui/segmented-control'
import { useImageCoverRect } from '@/composables/useImageCoverRect'
import { useMugTexture } from '@/composables/useMugTexture'
import { useToast } from '@/composables/useToast'
import { composeImage } from '@/lib/composeImage'
import { clampCropTransform } from '@/lib/cropTransform'
import type { Rect } from '@/lib/geometry'
import { useCartStore } from '@/stores/shop/cart'
import type { CropFrameTransform } from '@/stores/shop/cropFrame'
import { useEditorStore, type EditorDraft } from '@/stores/shop/editor'
import type { TextOverlay } from '@/stores/shop/textOverlays'
import mugModel from '@/assets/3d/mug.glb?url'
import ClipartsPlaceholder from './ClipartsPlaceholder.vue'
import CropFrameLayer from './CropFrameLayer.vue'
import ProductContextBar from './ProductContextBar.vue'
import ProductDraftUpload from './ProductDraftUpload.vue'
import TextOverlayLayer from './TextOverlayLayer.vue'
import TextToolPanel from './TextToolPanel.vue'
import type { EditorArticle, EditorArticleVariant } from './types'
import VariantGallery from './VariantGallery.vue'

const props = defineProps<{
  draft: EditorDraft
  article: EditorArticle
  variant: EditorArticleVariant
}>()

const { t } = useI18n()
const router = useRouter()
const editorStore = useEditorStore()
const cartStore = useCartStore()
const { toast } = useToast()

type EditorMode = 'edit' | 'preview'
type EditorToolId = 'text' | 'crop' | 'cliparts' | 'variants'

interface EditorTool {
  id: EditorToolId
  icon: Component
  labelKey: string
}

const imageContainerRef = ref<HTMLElement | null>(null)
const imageRef = ref<HTMLImageElement | null>(null)
const modelViewerRef = ref<HTMLElement | null>(null)

const activeMode = shallowRef<EditorMode>('edit')
const activeTool = shallowRef<EditorToolId | null>(null)
const selectedTextOverlayId = shallowRef<string | null>(null)
const isAddingToCart = shallowRef(false)
const modelViewerLoaded = shallowRef(false)
const modelReady = shallowRef(false)
const lastScreenFrameWidth = shallowRef(1)

const currentImage = computed(() =>
  editorStore.currentDraft?.id === props.draft.id ? editorStore.currentImage : null,
)

const hasCurrentImage = computed(() => currentImage.value != null)
const hasMugDimensions = computed(() => props.article.printArea != null)
const frameAspectRatio = computed(() => props.article.printArea?.aspectRatio ?? 1)

const textOverlays = computed(() => currentImage.value?.edits.textOverlays ?? [])
const cropTransform = computed(() => currentImage.value?.edits.cropTransform ?? defaultCrop())

const tools = computed<EditorTool[]>(() => {
  const list: EditorTool[] = [{ id: 'text', icon: markRaw(Type), labelKey: 'editor.tools.text' }]

  if (hasMugDimensions.value) {
    list.push({ id: 'crop', icon: markRaw(Crop), labelKey: 'editor.tools.crop' })
  }

  list.push({ id: 'cliparts', icon: markRaw(Sticker), labelKey: 'editor.tools.cliparts' })

  if (props.draft.images.length > 1) {
    list.push({ id: 'variants', icon: markRaw(Images), labelKey: 'editor.tools.variants' })
  }

  return list
})

const activeToolHasPanel = computed(
  () =>
    activeTool.value === 'text' ||
    activeTool.value === 'crop' ||
    activeTool.value === 'variants' ||
    activeTool.value === 'cliparts',
)

const isCropFrameVisible = computed(() => hasMugDimensions.value && activeTool.value === 'crop')
const isPreviewMode = computed(() => activeMode.value === 'preview')
const canAddToCart = computed(() => hasCurrentImage.value && !isAddingToCart.value)

const editImageRect = useImageCoverRect(imageContainerRef, imageRef)
const editFrameRect = ref<Rect>({ x: 0, y: 0, width: 0, height: 0 })

function updateEditFrameRect() {
  const container = imageContainerRef.value
  if (!container) return

  editFrameRect.value = { x: 0, y: 0, width: container.clientWidth, height: container.clientHeight }
}

watch(
  () => editImageRect.value,
  () => updateEditFrameRect(),
  { immediate: true },
)

watch(
  () => editFrameRect.value.width,
  (width) => {
    if (width > 0) lastScreenFrameWidth.value = width
  },
)

watch(tools, (list) => {
  if (activeTool.value && !list.some((tool) => tool.id === activeTool.value)) {
    activeTool.value = null
  }
})

watch(
  () => currentImage.value?.id ?? null,
  () => {
    selectedTextOverlayId.value = null
    activeTool.value = null
  },
)

watch(textOverlays, (overlays) => {
  if (
    selectedTextOverlayId.value &&
    !overlays.some((overlay) => overlay.id === selectedTextOverlayId.value)
  ) {
    selectedTextOverlayId.value = null
  }
})

watch(activeTool, (tool) => {
  if (tool !== 'text') {
    selectedTextOverlayId.value = null
  }
})

const effectiveCropTransform = computed(() =>
  clampCropTransform(cropTransform.value, editImageRect.value, editFrameRect.value),
)

useMugTexture({
  modelViewerRef,
  isPreviewMode,
  imageUrl: computed(() => currentImage.value?.url ?? null),
  screenFrameWidth: lastScreenFrameWidth,
  frameAspectRatio,
  cropTransform: computed(() => effectiveCropTransform.value),
  textOverlays,
})

const cropApplied = computed(() => {
  const transform = effectiveCropTransform.value
  return transform.scale !== 1 || transform.panX !== 0 || transform.panY !== 0
})

const imageTransformStyle = computed(() => {
  if (!cropApplied.value && activeTool.value !== 'crop') return undefined

  const { scale, panX, panY } = effectiveCropTransform.value
  if (scale === 1 && panX === 0 && panY === 0) return undefined

  return {
    transformOrigin: 'center',
    transform: `scale(${scale}) translate(${panX / scale}px, ${panY / scale}px)`,
  }
})

const printFrameStyle = computed(() => ({
  aspectRatio: `${frameAspectRatio.value}`,
}))

const printImageStyle = computed(() => {
  const image = editImageRect.value
  return {
    left: `${image.x}px`,
    top: `${image.y}px`,
    width: `${image.width}px`,
    height: `${image.height}px`,
    ...imageTransformStyle.value,
  }
})

const transformedImageBounds = computed(() => {
  const image = editImageRect.value
  const transform = effectiveCropTransform.value
  const scaledWidth = image.width * transform.scale
  const scaledHeight = image.height * transform.scale
  const centerX = image.x + image.width / 2 + transform.panX
  const centerY = image.y + image.height / 2 + transform.panY

  return {
    left: centerX - scaledWidth / 2,
    top: centerY - scaledHeight / 2,
    right: centerX + scaledWidth / 2,
    bottom: centerY + scaledHeight / 2,
  }
})

const cropOverscan = computed(() => {
  if (!isCropFrameVisible.value) return { x: 0, y: 0 }

  const frame = editFrameRect.value
  if (frame.width === 0 || frame.height === 0) return { x: 0, y: 0 }

  const bounds = transformedImageBounds.value
  const horizontalOverflow = Math.max(0, -bounds.left, bounds.right - frame.width)
  const verticalOverflow = Math.max(0, -bounds.top, bounds.bottom - frame.height)
  const horizontalBand = Math.min(48, Math.max(12, frame.width * 0.06))
  const verticalBand = Math.min(80, Math.max(16, frame.height * 0.18))

  return {
    x: Math.min(horizontalOverflow, horizontalBand),
    y: Math.min(verticalOverflow, verticalBand),
  }
})

const cropWorkspaceFrameRect = computed<Rect>(() => ({
  x: cropOverscan.value.x,
  y: cropOverscan.value.y,
  width: editFrameRect.value.width,
  height: editFrameRect.value.height,
}))

const cropWorkspaceStyle = computed(() => ({
  '--crop-overscan-x': `${cropOverscan.value.x}px`,
  '--crop-overscan-y': `${cropOverscan.value.y}px`,
}))

const cropPreviewImageStyle = computed(() => {
  const image = editImageRect.value
  const overscan = cropOverscan.value

  return {
    left: `${overscan.x + image.x}px`,
    top: `${overscan.y + image.y}px`,
    width: `${image.width}px`,
    height: `${image.height}px`,
    ...imageTransformStyle.value,
  }
})

function defaultCrop(): CropFrameTransform {
  return { scale: 1, panX: 0, panY: 0 }
}

function createTextOverlay(): TextOverlay {
  return {
    id: crypto.randomUUID(),
    text: t('editor.textTool.defaultText'),
    rx: 0.5,
    ry: 0.5,
    fontFamily: 'Plus Jakarta Sans',
    fontSize: 64,
    color: 'oklch(0.99 0 0)',
    bold: false,
    italic: false,
    underline: false,
    rotation: 0,
  }
}

function selectTool(id: EditorToolId) {
  activeTool.value = activeTool.value === id ? null : id
}

function closeActivePanel() {
  activeTool.value = null
}

function setTextOverlays(overlays: TextOverlay[]) {
  editorStore.updateCurrentImageEdits({ textOverlays: overlays })
}

function addTextOverlay() {
  const overlay = createTextOverlay()
  setTextOverlays([...textOverlays.value, overlay])
  selectedTextOverlayId.value = overlay.id
}

function removeTextOverlay(id: string) {
  setTextOverlays(textOverlays.value.filter((overlay) => overlay.id !== id))
  if (selectedTextOverlayId.value === id) {
    selectedTextOverlayId.value = null
  }
}

function updateTextOverlay(id: string, patch: Partial<Omit<TextOverlay, 'id'>>) {
  setTextOverlays(
    textOverlays.value.map((overlay) => (overlay.id === id ? { ...overlay, ...patch } : overlay)),
  )
}

function selectTextOverlay(id: string | null) {
  selectedTextOverlayId.value = id
}

function selectTextOverlayFromLayer(id: string | null) {
  selectTextOverlay(id)
  if (id !== null) {
    activeTool.value = 'text'
  }
}

function updateCropTransform(transform: CropFrameTransform) {
  editorStore.updateCurrentImageEdits({ cropTransform: transform })
}

function resetCropTransform() {
  updateCropTransform(defaultCrop())
}

function selectImageVariant(id: string) {
  editorStore.selectImage(id)
}

function handleUpload(file: File) {
  editorStore.addUploadedImageToDraft({
    draftId: props.draft.id,
    imageBlob: file,
  })
}

async function setMode(mode: EditorMode) {
  if (mode === 'preview') {
    lastScreenFrameWidth.value = editFrameRect.value.width || 1
  }

  activeMode.value = mode

  if (mode === 'preview') {
    activeTool.value = null
    modelReady.value = false
    if (!modelViewerLoaded.value) {
      await import('@google/model-viewer')
      modelViewerLoaded.value = true
    }
  }
}

function onModeChange(value: unknown) {
  if (value !== 'edit' && value !== 'preview') return

  void setMode(value)
}

async function handleAddToCart() {
  const image = currentImage.value
  if (!image || isAddingToCart.value) return

  isAddingToCart.value = true

  try {
    const canvas = await composeImage({
      imageUrl: image.url,
      frameAspectRatio: frameAspectRatio.value,
      cropTransform: effectiveCropTransform.value,
      screenFrameWidth: lastScreenFrameWidth.value || 1,
      textOverlays: image.edits.textOverlays,
    })

    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(
        (result) => (result ? resolve(result) : reject(new Error('Canvas toBlob failed'))),
        'image/png',
      )
    })

    await cartStore.addToCart(
      {
        articleId: props.draft.articleId,
        variantId: props.draft.variantId,
        quantity: 1,
      },
      blob,
    )

    await router.push({ name: 'cart' })
  } catch (error) {
    toast({
      title: error instanceof Error ? error.message : t('editor.addToCart.error'),
      variant: 'destructive',
    })
  } finally {
    isAddingToCart.value = false
  }
}
</script>

<template>
  <div class="product-editor grid gap-4">
    <ProductContextBar :article="article" :variant="variant" />

    <div class="editor-actionbar flex items-center justify-between gap-3 max-[640px]:gap-2">
      <SegmentedControl
        v-if="currentImage"
        :model-value="activeMode"
        type="single"
        variant="editor"
        class="edit-mode-toggle max-[640px]:min-w-0 max-[640px]:flex-none"
        @update:model-value="onModeChange"
      >
        <SegmentedControlItem
          value="edit"
          variant="editor"
          class="edit-mode-btn max-[640px]:gap-[0.2rem] max-[640px]:px-[0.45rem] max-[640px]:py-[0.35rem] max-[640px]:text-xs"
        >
          <Pencil class="h-[0.8rem] w-[0.8rem] sm:h-3.5 sm:w-3.5" />
          {{ t('editor.modes.edit') }}
        </SegmentedControlItem>
        <SegmentedControlItem
          value="preview"
          variant="editor"
          class="edit-mode-btn max-[640px]:gap-[0.2rem] max-[640px]:px-[0.45rem] max-[640px]:py-[0.35rem] max-[640px]:text-xs"
        >
          <Eye class="h-[0.8rem] w-[0.8rem] sm:h-3.5 sm:w-3.5" />
          {{ t('editor.modes.preview') }}
        </SegmentedControlItem>
      </SegmentedControl>

      <Button
        data-testid="editor-add-to-cart"
        class="editor-add-to-cart min-w-0 flex-1 justify-center gap-[0.35rem] whitespace-nowrap px-2.5 text-[0.8125rem] max-[430px]:size-9 max-[430px]:flex-none max-[430px]:gap-0 max-[430px]:px-0 [&_svg]:size-[0.9rem] sm:flex-none sm:gap-2 sm:px-4 sm:text-sm sm:[&_svg]:size-4"
        :disabled="!canAddToCart"
        @click="handleAddToCart"
      >
        <Loader2 v-if="isAddingToCart" class="size-4 animate-spin" />
        <ShoppingCart v-else class="size-4" />
        <span class="editor-add-to-cart-text max-[430px]:sr-only">
          {{ t('editor.addToCart.label') }}
        </span>
      </Button>
    </div>

    <ProductDraftUpload v-if="!currentImage" @upload="handleUpload" />

    <template v-else>
      <div
        v-if="activeMode === 'edit'"
        data-testid="editor-layout"
        class="editor-layout grid gap-4"
        :class="{ 'editor-layout--panel-open': activeToolHasPanel }"
      >
        <div
          data-testid="editor-workspace"
          class="editor-workspace flex min-w-0 items-center justify-center"
        >
          <div
            data-testid="editor-crop-workspace"
            class="editor-crop-workspace relative grid w-full min-w-0 place-items-center isolate"
            :class="{ 'editor-crop-workspace--active rounded-lg': isCropFrameVisible }"
            :style="cropWorkspaceStyle"
          >
            <img
              v-if="isCropFrameVisible"
              :src="currentImage.url"
              alt=""
              aria-hidden="true"
              class="editor-crop-preview-image pointer-events-none absolute z-0 block max-w-none select-none opacity-[0.38] saturate-[0.65] brightness-[0.82]"
              data-testid="editor-crop-preview-image"
              draggable="false"
              :style="cropPreviewImageStyle"
            />
            <div
              ref="imageContainerRef"
              data-testid="editor-print-frame"
              class="editor-print-frame relative z-[1] w-full min-w-0 overflow-hidden bg-background-soft"
              :style="printFrameStyle"
            >
              <img
                ref="imageRef"
                :src="currentImage.url"
                :alt="t('editor.imageAlt')"
                class="editor-print-image absolute block max-w-none select-none"
                data-testid="editor-print-image"
                draggable="false"
                :style="printImageStyle"
              />
              <TextOverlayLayer
                v-if="textOverlays.length > 0"
                :image-rect="editFrameRect"
                :interactive="activeTool === 'text'"
                :overlays="textOverlays"
                :selected-id="selectedTextOverlayId"
                @select="selectTextOverlayFromLayer"
                @update-overlay="updateTextOverlay"
              />
            </div>
            <CropFrameLayer
              v-if="hasMugDimensions"
              :image-rect="editImageRect"
              :frame-rect="cropWorkspaceFrameRect"
              :active="isCropFrameVisible"
              :interactive="activeTool === 'crop'"
              :transform="effectiveCropTransform"
              @update:transform="updateCropTransform"
            />
          </div>
        </div>

        <aside
          data-testid="editor-sidepanel"
          class="editor-sidepanel relative min-w-0"
          :class="{
            'editor-sidepanel--with-panel': activeToolHasPanel,
            'editor-sidepanel--rail-only': !activeToolHasPanel,
          }"
          :aria-label="t('editor.title')"
        >
          <div class="editor-control-shell min-w-0 rounded-xl">
            <div
              class="editor-toolbar scrollbar-hide flex items-center justify-start gap-0 overflow-x-auto bg-[var(--editor-control-rail-bg)] p-0 [scroll-padding-inline:0] md:w-[var(--editor-rail-width)] md:flex-col md:items-stretch md:self-stretch md:overflow-x-visible"
            >
              <Button
                v-for="(tool, index) in tools"
                :key="tool.id"
                type="button"
                variant="toolbar"
                size="toolbar"
                class="edit-tool-btn relative flex h-auto w-auto min-w-[4.75rem] flex-[1_0_4.75rem] flex-col items-center justify-center gap-2 rounded-none border-0 bg-transparent px-2.5 py-3 text-[var(--editor-control-button-text)] transition-[background,box-shadow,color,transform] duration-200 ease-out hover:transform-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[var(--editor-control-active-accent)] motion-safe:animate-enter-scale motion-reduce:animate-none motion-reduce:transition-none data-[state=active]:bg-[var(--editor-control-active-bg)] data-[state=active]:text-[var(--editor-control-active-text)] data-[state=active]:shadow-[inset_0_0_0_1px_color-mix(in_srgb,var(--brand)_18%,transparent)] data-[state=inactive]:hover:bg-[var(--editor-control-button-hover-bg)] data-[state=inactive]:hover:text-[var(--editor-control-button-strong)] md:w-full md:min-w-0 md:flex-none md:px-2"
                :class="{ 'edit-tool-btn--active': activeTool === tool.id }"
                :data-state="activeTool === tool.id ? 'active' : 'inactive'"
                :style="{ animationDelay: `${index * 50}ms` }"
                :aria-pressed="activeTool === tool.id"
                @click="selectTool(tool.id)"
              >
                <component
                  :is="tool.icon"
                  class="edit-tool-icon size-4 shrink-0 sm:size-5"
                  aria-hidden="true"
                />
                <span
                  class="edit-tool-label whitespace-normal text-center text-[0.625rem] leading-[1.1] sm:text-xs md:w-full md:font-bold md:leading-[1.15] md:[overflow-wrap:anywhere]"
                >
                  {{ t(tool.labelKey) }}
                </span>
              </Button>
            </div>

            <div
              v-if="activeToolHasPanel"
              data-testid="editor-active-tool-panel"
              class="editor-active-panel min-w-0 max-md:max-h-[var(--editor-mobile-panel-reserve)] max-md:overflow-y-auto max-md:bg-[var(--editor-control-panel-bg)] max-md:py-3.5 max-md:pl-3.5 max-md:pr-[3.25rem] max-md:[overscroll-behavior:contain] md:flex md:min-h-96 md:flex-col md:gap-4 md:bg-[var(--editor-control-panel-bg)] md:p-4"
            >
              <TextToolPanel
                v-if="activeTool === 'text'"
                class="editor-text-tool-panel md:rounded-none"
                :overlays="textOverlays"
                :selected-id="selectedTextOverlayId"
                @add-overlay="addTextOverlay"
                @remove-overlay="removeTextOverlay"
                @select-overlay="selectTextOverlay"
                @update-overlay="updateTextOverlay"
              />

              <div
                v-else-if="activeTool === 'crop'"
                class="crop-tool-panel flex items-center justify-center gap-3 text-sm text-muted-foreground max-md:mt-0 max-md:justify-start max-md:rounded-lg max-md:border max-md:border-[var(--editor-control-divider)] max-md:bg-[var(--editor-control-inset-bg)] max-md:p-3 max-md:text-left md:mt-0 md:min-h-full md:flex-col md:items-start md:justify-start md:rounded-lg md:border md:border-[var(--editor-control-divider)] md:bg-[var(--editor-control-inset-bg)] md:p-4 md:leading-6 md:text-[var(--editor-control-button-text)]"
              >
                <span class="crop-tool-copy">{{ t('editor.cropTool.hint') }}</span>
                <Button
                  v-if="cropApplied"
                  type="button"
                  variant="toolbar"
                  size="compact"
                  data-testid="editor-reset-crop"
                  class="crop-reset-btn inline-flex items-center gap-1 rounded-md border-0 bg-transparent text-xs font-semibold text-foreground hover:bg-muted md:rounded-lg md:border md:border-[var(--editor-control-divider)] md:bg-[var(--editor-control-button-bg)] md:text-[var(--editor-control-button-strong)] md:hover:bg-[var(--editor-control-button-hover-bg)]"
                  @click="resetCropTransform"
                >
                  <RotateCcw class="h-3 w-3" />
                  {{ t('editor.cropTool.reset') }}
                </Button>
              </div>

              <ClipartsPlaceholder
                v-else-if="activeTool === 'cliparts'"
                class="editor-cliparts-tool-panel"
              />

              <VariantGallery
                v-else-if="activeTool === 'variants'"
                class="editor-variant-gallery mt-3 sm:justify-center md:justify-start"
                :images="draft.images"
                :selected-image-id="draft.selectedImageId"
                @select="selectImageVariant"
              />
            </div>
          </div>

          <Button
            v-if="activeToolHasPanel"
            type="button"
            variant="toolbar"
            size="toolbar-sm"
            data-testid="editor-close-panel"
            class="editor-panel-close hidden max-md:rounded-t-xl md:rounded-r-xl motion-reduce:transition-none"
            :aria-label="t('editor.closePanel')"
            :title="t('editor.closePanel')"
            @click="closeActivePanel"
          >
            <PanelLeftClose
              class="editor-panel-close-icon h-3.5 w-3.5 max-md:-rotate-90"
              aria-hidden="true"
            />
          </Button>
        </aside>
      </div>

      <div
        v-if="activeMode === 'preview'"
        data-testid="editor-preview-workspace"
        class="flex items-center justify-center"
      >
        <div class="relative h-[55vh] w-full sm:h-[65vh]">
          <div v-if="!modelReady" class="absolute inset-0 flex items-center justify-center">
            <Loader2 class="size-8 animate-spin text-muted-foreground" />
          </div>
          <model-viewer
            ref="modelViewerRef"
            :src="mugModel"
            :alt="t('editor.preview3dAlt')"
            camera-controls
            auto-rotate
            camera-orbit="0deg 75deg auto"
            min-camera-orbit="auto auto 200%"
            max-camera-orbit="auto auto 250%"
            shadow-intensity="1"
            environment-image="neutral"
            class="h-full w-full transition-opacity duration-500"
            :class="modelReady ? 'opacity-100' : 'opacity-0'"
            @load="modelReady = true"
          />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* CSS exceptions: editor geometry variables, behavior-coupled rail/panel layout,
   crop overscan effects, child-panel overrides, sibling dividers, active-tool
   indicator, and close-tab positioning stay local because Tailwind utility
   chains would be less readable and easier to regress. */
.product-editor {
  --editor-control-border: color-mix(in srgb, var(--border) 88%, transparent);
  --editor-control-divider: color-mix(in srgb, var(--border) 72%, transparent);
  --editor-control-rail-bg: color-mix(in srgb, var(--background-soft) 88%, var(--foreground) 5%);
  --editor-control-panel-bg: color-mix(in srgb, var(--background) 88%, var(--background-soft) 12%);
  --editor-control-inset-bg: color-mix(in srgb, var(--muted) 68%, var(--background) 32%);
  --editor-control-button-bg: color-mix(in srgb, var(--background) 72%, var(--muted) 28%);
  --editor-control-button-hover-bg: color-mix(in srgb, var(--background) 58%, var(--muted) 42%);
  --editor-control-button-text: var(--muted-foreground);
  --editor-control-button-strong: var(--foreground);
  --editor-control-active-bg: color-mix(in srgb, var(--brand) 14%, var(--background) 86%);
  --editor-control-active-text: var(--foreground);
  --editor-control-active-accent: var(--brand);
  --editor-control-shadow: 0 18px 44px color-mix(in srgb, var(--foreground) 8%, transparent);
  --editor-rail-width: 5.5rem;
  --editor-panel-width: clamp(20rem, 29vw, 26.25rem);
  --editor-mobile-toolbar-reserve: calc(6.75rem + env(safe-area-inset-bottom, 0px));
  --editor-mobile-panel-reserve: min(58dvh, 28rem);
  --editor-mobile-panel-layout-reserve: min(12rem, 22dvh);
}

.dark .product-editor {
  --editor-control-border: color-mix(in srgb, var(--border) 92%, transparent);
  --editor-control-divider: color-mix(in srgb, var(--border) 82%, transparent);
  --editor-control-rail-bg: color-mix(in srgb, var(--background-soft) 82%, black 18%);
  --editor-control-panel-bg: color-mix(in srgb, var(--background-soft) 90%, black 10%);
  --editor-control-inset-bg: color-mix(in srgb, var(--muted) 74%, white 4%);
  --editor-control-button-bg: color-mix(in srgb, var(--background-soft) 78%, white 5%);
  --editor-control-button-hover-bg: color-mix(in srgb, var(--background-soft) 68%, white 12%);
  --editor-control-active-bg: color-mix(in srgb, var(--brand) 25%, var(--background-soft) 75%);
  --editor-control-active-text: var(--foreground);
  --editor-control-shadow: 0 18px 44px oklch(0 0 0 / 0.28);
}

.editor-layout {
  grid-template-areas:
    'workspace'
    'tools';
  padding-bottom: var(--editor-mobile-toolbar-reserve);
}

.editor-layout--panel-open {
  padding-bottom: calc(
    var(--editor-mobile-toolbar-reserve) + var(--editor-mobile-panel-layout-reserve)
  );
}

.editor-workspace {
  grid-area: workspace;
}

.editor-crop-workspace {
  --crop-overscan-x: 0px;
  --crop-overscan-y: 0px;
}

.editor-crop-workspace--active {
  width: calc(100% + var(--crop-overscan-x) + var(--crop-overscan-x));
  flex-shrink: 0;
  padding: var(--crop-overscan-y) var(--crop-overscan-x);
  overflow: hidden;
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--background-soft) 86%, var(--foreground) 8%),
    color-mix(in srgb, var(--background-soft) 94%, var(--brand) 6%)
  );
}

.editor-crop-workspace--active .editor-print-frame {
  box-shadow:
    0 0 0 1px color-mix(in srgb, white 76%, var(--brand) 24%),
    0 8px 22px color-mix(in srgb, var(--foreground) 14%, transparent);
}

.editor-sidepanel {
  grid-area: tools;
}

@media (max-width: 767.98px) {
  .editor-sidepanel {
    position: fixed;
    right: max(0.75rem, env(safe-area-inset-right, 0px));
    bottom: max(0.75rem, env(safe-area-inset-bottom, 0px));
    left: max(0.75rem, env(safe-area-inset-left, 0px));
    z-index: 40;
  }

  .editor-control-shell {
    display: flex;
    min-width: 0;
    flex-direction: column-reverse;
    overflow: hidden;
    border: 1px solid var(--editor-control-border);
    background: var(--editor-control-panel-bg);
    box-shadow: var(--editor-control-shadow);
  }

  .editor-sidepanel--rail-only .editor-control-shell {
    background: var(--editor-control-rail-bg);
  }

  .editor-sidepanel--with-panel .editor-toolbar {
    border-top: 1px solid var(--editor-control-divider);
  }

  .editor-active-panel :deep(.text-tool-panel) {
    margin: 0;
  }

  .editor-active-panel :deep(.cliparts-placeholder) {
    margin-top: 0;
  }

  .editor-active-panel :deep(.editor-variant-gallery) {
    margin-top: 0;
    justify-content: flex-start;
  }

  .edit-tool-btn + .edit-tool-btn {
    border-left: 1px solid var(--editor-control-divider);
  }

  .edit-tool-btn--active::before {
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    height: 4px;
    background: var(--editor-control-active-accent);
    content: '';
  }

  .editor-panel-close {
    position: absolute;
    top: -1.5rem;
    right: 1rem;
    z-index: 2;
    display: inline-flex;
    width: 4rem;
    height: 1.5rem;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--editor-control-border);
    border-bottom: 0;
    background: var(--editor-control-panel-bg);
    box-shadow: 0 -10px 24px color-mix(in srgb, var(--foreground) 8%, transparent);
    color: var(--editor-control-button-text);
    cursor: pointer;
    transform: none;
    transition:
      background 0.15s ease,
      color 0.15s ease,
      height 0.15s ease;
  }

  .editor-panel-close:hover {
    height: 1.75rem;
    background: var(--editor-control-button-hover-bg);
    color: var(--editor-control-button-strong);
  }

  .editor-panel-close:focus-visible {
    outline: 2px solid var(--editor-control-active-accent);
    outline-offset: 2px;
  }
}

@media (min-width: 768px) {
  .editor-layout,
  .editor-layout--panel-open {
    padding-bottom: 0;
  }

  .editor-layout {
    grid-template-areas: 'tools workspace';
    grid-template-columns: max-content minmax(0, 1fr);
    gap: clamp(1rem, 2vw, 2rem);
    align-items: start;
  }

  .editor-sidepanel {
    width: var(--editor-rail-width);
    min-width: var(--editor-rail-width);
    transition:
      width 0.2s ease,
      min-width 0.2s ease;
  }

  .editor-control-shell {
    display: grid;
    width: 100%;
    overflow: hidden;
    border: 1px solid var(--editor-control-border);
    background: var(--editor-control-rail-bg);
    box-shadow: var(--editor-control-shadow);
  }

  .editor-sidepanel--with-panel {
    width: calc(var(--editor-rail-width) + var(--editor-panel-width));
    min-width: calc(var(--editor-rail-width) + var(--editor-panel-width));
  }

  .editor-sidepanel--with-panel .editor-control-shell {
    grid-template-columns: var(--editor-rail-width) minmax(20rem, var(--editor-panel-width));
    background: var(--editor-control-panel-bg);
  }

  .editor-sidepanel--rail-only .editor-control-shell {
    grid-template-columns: var(--editor-rail-width);
  }

  .editor-sidepanel--with-panel .editor-toolbar {
    border-right: 1px solid var(--editor-control-divider);
  }

  .crop-tool-copy {
    color: var(--muted-foreground);
  }

  .editor-active-panel :deep(.text-tool-panel) {
    margin: 0;
    padding: 0;
    border: 0;
    background: transparent;
  }

  .editor-active-panel :deep(.text-tool-panel > .mb-3) {
    margin-bottom: 1rem;
    padding-bottom: 1rem;
    border-bottom: 1px solid var(--editor-control-divider);
  }

  .editor-active-panel :deep(.text-tool-hint) {
    padding: 0.875rem;
    border: 1px solid var(--editor-control-divider);
    background: var(--editor-control-inset-bg);
    text-align: left;
  }

  .editor-active-panel :deep(.cliparts-placeholder) {
    margin-top: 0;
    border-style: solid;
    background: var(--editor-control-inset-bg);
  }

  .editor-active-panel :deep(.editor-variant-gallery) {
    margin-top: 0;
    gap: 0.75rem;
    align-content: flex-start;
  }

  .editor-active-panel :deep(.editor-variant-gallery .vg-thumb) {
    width: 76px;
    height: 76px;
  }

  .edit-tool-btn + .edit-tool-btn {
    border-top: 1px solid var(--editor-control-divider);
  }

  .edit-tool-btn--active::before {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    width: 4px;
    background: var(--editor-control-active-accent);
    content: '';
  }

  .editor-panel-close {
    position: absolute;
    top: 50%;
    right: -1.5rem;
    z-index: 2;
    display: inline-flex;
    width: 1.5rem;
    height: 4rem;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--editor-control-border);
    border-left: 0;
    background: var(--editor-control-panel-bg);
    box-shadow: 10px 12px 24px color-mix(in srgb, var(--foreground) 8%, transparent);
    color: var(--editor-control-button-text);
    cursor: pointer;
    transform: translateY(-50%);
    transition:
      background 0.15s ease,
      color 0.15s ease,
      width 0.15s ease;
  }

  .editor-panel-close:hover {
    width: 1.75rem;
    background: var(--editor-control-button-hover-bg);
    color: var(--editor-control-button-strong);
  }

  .editor-panel-close:focus-visible {
    outline: 2px solid var(--editor-control-active-accent);
    outline-offset: 2px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .editor-panel-close {
    transition: none;
  }
}
</style>
