<script setup lang="ts">
import { computed, nextTick, onMounted, shallowRef, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Bold, Italic, Plus, RotateCw, Trash2, Underline } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { ColorInput } from '@/components/ui/color-input'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  type SelectAcceptableValue,
} from '@/components/ui/select'
import { Slider, SliderRange, SliderThumb, SliderTrack } from '@/components/ui/slider'
import { SwatchButton } from '@/components/ui/swatch-button'
import type { TextOverlay } from '@/stores/shop/textOverlays'

const props = defineProps<{
  overlays: TextOverlay[]
  selectedId: string | null
}>()

const emit = defineEmits<{
  addOverlay: []
  removeOverlay: [id: string]
  selectOverlay: [id: string | null]
  updateOverlay: [id: string, patch: Partial<Omit<TextOverlay, 'id'>>]
}>()

const { t } = useI18n()

const textInputRef = useTemplateRef<InstanceType<typeof Input>>('textInput')
const customColorHex = shallowRef('#000000')

const FONTS = [
  { value: 'Plus Jakarta Sans', label: 'Jakarta Sans' },
  { value: 'Fraunces', label: 'Fraunces' },
  { value: 'Pacifico', label: 'Pacifico' },
  { value: 'Permanent Marker', label: 'Permanent Marker' },
  { value: 'Playfair Display', label: 'Playfair Display' },
  { value: 'Lobster', label: 'Lobster' },
  { value: 'Dancing Script', label: 'Dancing Script' },
  { value: 'Bebas Neue', label: 'Bebas Neue' },
  { value: 'Caveat', label: 'Caveat' },
  { value: 'Abril Fatface', label: 'Abril Fatface' },
]

const COLOR_PRESETS = [
  'oklch(0.15 0 0)',
  'oklch(0.99 0 0)',
  'oklch(0.61 0.19 35)',
  'oklch(0.7 0.2 50)',
  'oklch(0.55 0.22 25)',
  'oklch(0.6 0.18 145)',
  'oklch(0.55 0.18 255)',
  'oklch(0.5 0.18 300)',
  'oklch(0.7 0.15 85)',
  'oklch(0.6 0.01 0)',
]

const selectedOverlay = computed(
  () => props.overlays.find((overlay) => overlay.id === props.selectedId) ?? null,
)

const fontSizeSliderValue = computed(() =>
  selectedOverlay.value ? [selectedOverlay.value.fontSize] : [32],
)

const rotationSliderValue = computed(() =>
  selectedOverlay.value ? [selectedOverlay.value.rotation] : [0],
)

async function loadFont(fontFamily: string) {
  const builtIn = ['Plus Jakarta Sans', 'Fraunces']
  if (builtIn.includes(fontFamily)) return

  const id = `font-${fontFamily.replace(/\s+/g, '-').toLowerCase()}`
  if (document.getElementById(id)) return

  const link = document.createElement('link')
  link.id = id
  link.rel = 'stylesheet'
  link.href = `https://fonts.googleapis.com/css2?family=${encodeURIComponent(fontFamily)}&display=swap`
  document.head.appendChild(link)

  try {
    await document.fonts?.load?.(`16px "${fontFamily}"`)
  } catch {
    // The browser can still use its fallback if a web font fails.
  }
}

function addText() {
  emit('addOverlay')
  nextTick(() => {
    const input = textInputRef.value?.$el as HTMLInputElement | undefined
    input?.focus()
    input?.select()
  })
}

function deleteSelected() {
  if (!selectedOverlay.value) return

  emit('removeOverlay', selectedOverlay.value.id)
}

function updateSelected(patch: Partial<Omit<TextOverlay, 'id'>>) {
  if (!selectedOverlay.value) return

  emit('updateOverlay', selectedOverlay.value.id, patch)
}

function onTextInput(value: string | number) {
  updateSelected({ text: String(value) })
}

function onFontChange(value: SelectAcceptableValue) {
  if (typeof value !== 'string') return

  void loadFont(value)
  updateSelected({ fontFamily: value })
}

function onFontSizeChange(value: number[] | undefined) {
  if (!value?.length) return

  updateSelected({ fontSize: value[0] })
}

function onRotationChange(value: number[] | undefined) {
  if (!value?.length) return

  updateSelected({ rotation: value[0] })
}

function toggleStyle(key: 'bold' | 'italic' | 'underline') {
  const overlay = selectedOverlay.value
  if (!overlay) return

  updateSelected({ [key]: !overlay[key] })
}

function setColor(color: string) {
  updateSelected({ color })
}

function onCustomColor(hex: string) {
  customColorHex.value = hex
  setColor(hex)
}

onMounted(() => {
  FONTS.forEach((font) => void loadFont(font.value))
})

watch(
  () => selectedOverlay.value?.fontFamily,
  (font) => {
    if (font) void loadFont(font)
  },
  { immediate: true },
)
</script>

<template>
  <div
    class="text-tool-panel mt-3 rounded-lg animate-panel-in"
    data-testid="editor-text-tool-panel"
  >
    <div class="mb-3 flex items-center justify-between gap-2">
      <Button variant="outline" size="sm" data-testid="editor-add-text" @click="addText">
        <Plus class="h-3.5 w-3.5" />
        {{ t('editor.textTool.addText') }}
      </Button>
      <Button v-if="selectedOverlay" variant="destructive" size="sm" @click="deleteSelected">
        <Trash2 class="h-3.5 w-3.5" />
        {{ t('editor.textTool.deleteText') }}
      </Button>
    </div>

    <p v-if="!selectedOverlay && overlays.length > 0" class="text-tool-hint md:rounded-xl">
      {{ t('editor.textTool.tapToEdit') }}
    </p>

    <div v-if="selectedOverlay" class="space-y-3">
      <div>
        <Label for="editor-text-tool-input" class="text-tool-label">
          {{ t('editor.textTool.textPlaceholder') }}
        </Label>
        <Input
          id="editor-text-tool-input"
          ref="textInput"
          :model-value="selectedOverlay.text"
          @update:model-value="onTextInput"
        />
      </div>

      <div>
        <Label class="text-tool-label">
          {{ t('editor.textTool.fontFamily') }}
        </Label>
        <Select :model-value="selectedOverlay.fontFamily" @update:model-value="onFontChange">
          <SelectTrigger class="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem
              v-for="font in FONTS"
              :key="font.value"
              :value="font.value"
              :style="{ fontFamily: font.value }"
            >
              {{ font.label }}
            </SelectItem>
          </SelectContent>
        </Select>
      </div>

      <div>
        <div class="mb-2 flex items-center gap-3">
          <Label class="text-tool-label mb-0">
            {{ t('editor.textTool.fontSize') }}
          </Label>
          <span class="text-tool-size-display">{{ selectedOverlay.fontSize }}</span>
          <div class="ml-auto flex gap-1">
            <Button
              variant="outline"
              size="icon-sm"
              :class="selectedOverlay.bold ? 'text-tool-toggle--active' : ''"
              @click="toggleStyle('bold')"
            >
              <Bold class="h-3.5 w-3.5" />
            </Button>
            <Button
              variant="outline"
              size="icon-sm"
              :class="selectedOverlay.italic ? 'text-tool-toggle--active' : ''"
              @click="toggleStyle('italic')"
            >
              <Italic class="h-3.5 w-3.5" />
            </Button>
            <Button
              variant="outline"
              size="icon-sm"
              :class="selectedOverlay.underline ? 'text-tool-toggle--active' : ''"
              @click="toggleStyle('underline')"
            >
              <Underline class="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
        <Slider
          :model-value="fontSizeSliderValue"
          :min="10"
          :max="200"
          :step="1"
          class="w-full"
          @update:model-value="onFontSizeChange"
        >
          <SliderTrack class="text-tool-slider-track">
            <SliderRange class="text-tool-slider-range" />
          </SliderTrack>
          <SliderThumb class="text-tool-slider-thumb" />
        </Slider>
      </div>

      <div>
        <div class="mb-2 flex items-center gap-3">
          <RotateCw class="h-3.5 w-3.5 text-muted-foreground" />
          <Label class="text-tool-label mb-0">
            {{ t('editor.textTool.rotation') }}
          </Label>
          <span class="text-tool-size-display">{{ selectedOverlay.rotation }} deg</span>
        </div>
        <Slider
          :model-value="rotationSliderValue"
          :min="-180"
          :max="180"
          :step="1"
          class="w-full"
          @update:model-value="onRotationChange"
        >
          <SliderTrack class="text-tool-slider-track">
            <SliderRange class="text-tool-slider-range" />
          </SliderTrack>
          <SliderThumb class="text-tool-slider-thumb" />
        </Slider>
      </div>

      <div>
        <Label class="text-tool-label">
          {{ t('editor.textTool.color') }}
        </Label>
        <div class="flex flex-wrap items-center gap-1.5">
          <SwatchButton
            v-for="color in COLOR_PRESETS"
            :key="color"
            class="text-tool-color-swatch rounded-full"
            :color="color"
            :label="`${t('editor.textTool.color')}: ${color}`"
            :selected="selectedOverlay.color === color"
            @click="setColor(color)"
          />
          <ColorInput
            v-model="customColorHex"
            visually-hidden
            :label="t('editor.textTool.customColor')"
            trigger-class="text-tool-color-custom rounded-full"
            @update:model-value="onCustomColor"
          >
            <span class="text-tool-color-custom-indicator rounded-full" aria-hidden="true" />
          </ColorInput>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.text-tool-panel {
  padding: 12px;
  border: 1px solid oklch(0.92 0.01 0 / 0.6);
  background: oklch(0.97 0.005 0 / 0.8);
}

.dark .text-tool-panel {
  border-color: var(--border);
  background: oklch(0.22 0.005 0 / 0.8);
}

.animate-panel-in {
  animation: panel-slide-in 0.3s cubic-bezier(0.16, 1, 0.3, 1) both;
}

@keyframes panel-slide-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.text-tool-label {
  display: block;
  margin-bottom: 4px;
  color: var(--muted-foreground);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0;
  text-transform: uppercase;
}

.text-tool-hint {
  padding: 8px 0;
  color: var(--muted-foreground);
  font-size: 13px;
  text-align: center;
}

.text-tool-size-display {
  color: var(--foreground);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.text-tool-slider-track {
  height: 6px;
}

.text-tool-slider-range {
  background: linear-gradient(90deg, oklch(0.61 0.19 35), oklch(0.7 0.2 50));
}

.text-tool-slider-thumb {
  width: 22px;
  height: 22px;
  border-color: oklch(0.61 0.19 35);
  cursor: grab;
}

.text-tool-slider-thumb:active {
  transform: scale(1.1);
  cursor: grabbing;
}

.text-tool-toggle--active {
  border-color: transparent;
  background: var(--brand-gradient);
  color: white;
}

.text-tool-color-swatch {
  width: 24px;
  height: 24px;
  padding: 2px;
  border: 2px solid var(--border);
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    transform 0.15s ease;
}

.text-tool-color-swatch:hover {
  transform: scale(1.15);
}

.text-tool-color-swatch[data-state='selected'] {
  border-color: oklch(0.61 0.19 35);
  box-shadow: 0 0 0 2px oklch(0.61 0.19 35 / 0.3);
}

:deep(.text-tool-color-custom) {
  width: 24px;
  height: 24px;
  border: 2px solid var(--border);
  background: conic-gradient(
    oklch(0.65 0.27 30),
    oklch(0.75 0.2 90),
    oklch(0.7 0.2 145),
    oklch(0.6 0.2 255),
    oklch(0.55 0.25 300),
    oklch(0.65 0.27 30)
  );
  cursor: pointer;
  transition: transform 0.15s ease;
}

:deep(.text-tool-color-custom:hover) {
  transform: scale(1.15);
}

.text-tool-color-custom-indicator {
  display: block;
  width: 100%;
  height: 100%;
  background: conic-gradient(
    oklch(0.65 0.27 30),
    oklch(0.75 0.2 90),
    oklch(0.7 0.2 145),
    oklch(0.6 0.2 255),
    oklch(0.55 0.25 300),
    oklch(0.65 0.27 30)
  );
}

@media (prefers-reduced-motion: reduce) {
  .animate-panel-in {
    animation: none;
  }

  .text-tool-color-swatch,
  :deep(.text-tool-color-custom),
  .text-tool-slider-thumb {
    transition: none;
  }

  .text-tool-slider-thumb:active {
    transform: none;
  }
}
</style>
