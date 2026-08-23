<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { PRINT_ASPECT_RATIOS } from '@/components/shop/editor/types'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { TshirtPrintAspectRatio, TshirtPrintFrameDto } from '@/stores/admin/tshirtArticles'

/**
 * Calibrates the rectangle the generated design is printed in, in percent of the product mockup.
 *
 * The shop editor places the design at exactly these four percentages over exactly this mockup
 * (issue #218), so what is drawn here is what a customer sees — including its shape. That is the
 * reason for the aspect lock below: the printed image is generated in the article's
 * `printAspectRatio`, and a rectangle of a different shape would show the customer a frame the
 * composed print does not fill.
 *
 * The mockup is not square, so the shape of the rectangle is not `widthPct : heightPct`. It is
 * `widthPct * mockupWidth : heightPct * mockupHeight`, which is why the lock only works once the
 * mockup image has reported its natural size.
 */
interface Props {
  frame: TshirtPrintFrameDto
  printAspectRatio: TshirtPrintAspectRatio
  /** The photo the frame is calibrated on — the default variant's mockup, when one is uploaded. */
  mockupUrl: string | null
  /** Backend messages, keyed by the JSON path the write reported them on. */
  errors?: Partial<
    Record<
      'printFrame.leftPct' | 'printFrame.topPct' | 'printFrame.widthPct' | 'printFrame.heightPct',
      string
    >
  >
}

const props = withDefaults(defineProps<Props>(), {
  errors: () => ({}),
})

const emit = defineEmits<{
  (event: 'update:frame', frame: TshirtPrintFrameDto): void
}>()

/** How far the drawn shape may differ from the print shape before the calibrator says so. */
const RATIO_TOLERANCE = 0.02

const keepAspectRatio = ref(true)
const mockupWidth = shallowRef(0)
const mockupHeight = shallowRef(0)

/** The mockup's own shape. `null` until the picture has loaded — nothing can be derived before. */
const mockupRatio = computed(() =>
  mockupWidth.value > 0 && mockupHeight.value > 0 ? mockupWidth.value / mockupHeight.value : null,
)

const printRatio = computed(() => PRINT_ASPECT_RATIOS[props.printAspectRatio])

/** The shape the drawn rectangle actually has, or `null` while the mockup size is unknown. */
const drawnRatio = computed(() => {
  const ratio = mockupRatio.value
  if (ratio === null || props.frame.heightPct <= 0) {
    return null
  }

  return (props.frame.widthPct * ratio) / props.frame.heightPct
})

const isRatioOff = computed(() => {
  const drawn = drawnRatio.value
  return drawn !== null && Math.abs(drawn / printRatio.value - 1) > RATIO_TOLERANCE
})

const canDeriveHeight = computed(() => mockupRatio.value !== null)

const previewStyle = computed(() => ({
  left: `${props.frame.leftPct}%`,
  top: `${props.frame.topPct}%`,
  width: `${props.frame.widthPct}%`,
  height: `${props.frame.heightPct}%`,
}))

function onMockupLoad(event: Event) {
  const image = event.target as HTMLImageElement
  mockupWidth.value = image.naturalWidth
  mockupHeight.value = image.naturalHeight
}

/** The stored columns are `numeric(5, 2)`, so nothing finer than two decimals survives a save. */
function rounded(value: number) {
  return Math.round(value * 100) / 100
}

function clampPercent(value: number) {
  if (!Number.isFinite(value)) {
    return 0
  }

  return Math.min(100, Math.max(0, rounded(value)))
}

/** The height that gives the rectangle the print's shape, for the current width. */
function heightForWidth(widthPct: number) {
  const ratio = mockupRatio.value
  return ratio === null ? null : clampPercent((widthPct * ratio) / printRatio.value)
}

/** The width that gives the rectangle the print's shape, for the current height. */
function widthForHeight(heightPct: number) {
  const ratio = mockupRatio.value
  return ratio === null ? null : clampPercent((heightPct * printRatio.value) / ratio)
}

function parsePercent(value: string | number) {
  const parsed = Number(String(value).replace(',', '.'))
  return Number.isFinite(parsed) ? clampPercent(parsed) : 0
}

function update(patch: Partial<TshirtPrintFrameDto>) {
  emit('update:frame', { ...props.frame, ...patch })
}

function setLeft(value: string | number) {
  update({ leftPct: parsePercent(value) })
}

function setTop(value: string | number) {
  update({ topPct: parsePercent(value) })
}

function setWidth(value: string | number) {
  const widthPct = parsePercent(value)
  const heightPct = keepAspectRatio.value ? heightForWidth(widthPct) : null
  update(heightPct === null ? { widthPct } : { widthPct, heightPct })
}

function setHeight(value: string | number) {
  const heightPct = parsePercent(value)
  const widthPct = keepAspectRatio.value ? widthForHeight(heightPct) : null
  update(widthPct === null ? { heightPct } : { heightPct, widthPct })
}

/** Corrects the height so that the drawn rectangle has the shape the print is generated in. */
function fitToPrintRatio() {
  const heightPct = heightForWidth(props.frame.widthPct)
  if (heightPct !== null) {
    update({ heightPct })
  }
}

defineExpose({ fitToPrintRatio })
</script>

<template>
  <div class="grid gap-5 lg:grid-cols-2">
    <div class="space-y-4">
      <div class="grid gap-4 sm:grid-cols-2">
        <FormField
          label="Left (%)"
          for="tshirt-frame-left"
          :error="props.errors['printFrame.leftPct']"
        >
          <Input
            id="tshirt-frame-left"
            :model-value="props.frame.leftPct"
            type="number"
            min="0"
            max="100"
            step="0.01"
            data-testid="print-frame-left"
            @update:model-value="setLeft"
          />
        </FormField>
        <FormField
          label="Top (%)"
          for="tshirt-frame-top"
          :error="props.errors['printFrame.topPct']"
        >
          <Input
            id="tshirt-frame-top"
            :model-value="props.frame.topPct"
            type="number"
            min="0"
            max="100"
            step="0.01"
            data-testid="print-frame-top"
            @update:model-value="setTop"
          />
        </FormField>
        <FormField
          label="Width (%)"
          for="tshirt-frame-width"
          :error="props.errors['printFrame.widthPct']"
        >
          <Input
            id="tshirt-frame-width"
            :model-value="props.frame.widthPct"
            type="number"
            min="0"
            max="100"
            step="0.01"
            data-testid="print-frame-width"
            @update:model-value="setWidth"
          />
        </FormField>
        <FormField
          label="Height (%)"
          for="tshirt-frame-height"
          :error="props.errors['printFrame.heightPct']"
        >
          <Input
            id="tshirt-frame-height"
            :model-value="props.frame.heightPct"
            type="number"
            min="0"
            max="100"
            step="0.01"
            data-testid="print-frame-height"
            @update:model-value="setHeight"
          />
        </FormField>
      </div>

      <div class="flex items-start gap-3">
        <Checkbox id="tshirt-frame-keep-ratio" v-model="keepAspectRatio" />
        <div>
          <Label for="tshirt-frame-keep-ratio">Keep the print aspect ratio</Label>
          <p class="text-sm text-muted-foreground">
            The shop places the design at these four percentages, so the frame must have the shape
            the image is generated in ({{ props.printAspectRatio }}). Editing width or height then
            corrects the other side.
          </p>
        </div>
      </div>

      <Button
        type="button"
        variant="outline"
        size="sm"
        :disabled="!canDeriveHeight"
        data-testid="print-frame-fit"
        @click="fitToPrintRatio"
      >
        Fit height to {{ props.printAspectRatio }}
      </Button>

      <Alert v-if="!canDeriveHeight" variant="info" data-testid="print-frame-no-mockup">
        Upload the example image of the default variant to calibrate the frame on the real mockup.
        Without it the frame's shape cannot be checked against the print ratio.
      </Alert>
      <Alert v-else-if="isRatioOff" variant="destructive" data-testid="print-frame-ratio-warning">
        The frame is not shaped like a {{ props.printAspectRatio }} print. The shop would show a
        frame the generated image does not fill — use "Fit height" or leave the ratio lock on.
      </Alert>
    </div>

    <div
      class="relative overflow-hidden rounded-lg border border-border bg-muted/10"
      data-testid="print-frame-preview"
    >
      <img
        v-if="props.mockupUrl"
        :src="props.mockupUrl"
        alt="Product mockup of the default variant"
        class="block w-full"
        data-testid="print-frame-mockup"
        @load="onMockupLoad"
      />
      <div v-else class="aspect-square w-full" />
      <div
        class="absolute border-2 border-dashed border-primary bg-primary/10"
        :style="previewStyle"
        data-testid="print-frame-rectangle"
      />
    </div>
  </div>
</template>
