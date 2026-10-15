<script setup lang="ts">
import { computed } from 'vue'
import { Alert } from '@/components/ui/alert'
import type { PrintFrameDto } from '@/stores/shop/catalog'

/**
 * Shows the rectangle the generated design is printed in, as the last sync measured it from the
 * SPOD hotspot preview. Nothing here is editable: the frame is computed by the sync, never by an
 * admin, and the shop editor places the design at exactly these four percentages over exactly this
 * mockup — so what is drawn here is what a customer sees.
 */
const props = defineProps<{
  /** `null` when the last sync could not measure the frame. */
  frame: PrintFrameDto | null
  /** The SPOD hotspot the frame was measured from; set exactly when `frame` is. */
  hotspot: string | null
  /** The plain product photo of the default colour, the backdrop of the shop editor. */
  mockupUrl: string | null
}>()

const rectangleStyle = computed(() =>
  props.frame
    ? {
        left: `${props.frame.leftPct}%`,
        top: `${props.frame.topPct}%`,
        width: `${props.frame.widthPct}%`,
        height: `${props.frame.heightPct}%`,
      }
    : null,
)
</script>

<template>
  <Alert v-if="!props.frame" variant="destructive" data-testid="print-frame-missing">
    The last sync could not measure the print frame; see the sync report. The article cannot be
    activated.
  </Alert>

  <div v-else class="grid gap-5 lg:grid-cols-2">
    <dl class="grid content-start gap-4 sm:grid-cols-2" data-testid="print-frame-values">
      <div class="sm:col-span-2">
        <dt class="text-sm text-muted-foreground">Hotspot</dt>
        <dd class="text-foreground">{{ props.hotspot }}</dd>
      </div>
      <div>
        <dt class="text-sm text-muted-foreground">Left</dt>
        <dd class="text-foreground">{{ props.frame.leftPct }} %</dd>
      </div>
      <div>
        <dt class="text-sm text-muted-foreground">Top</dt>
        <dd class="text-foreground">{{ props.frame.topPct }} %</dd>
      </div>
      <div>
        <dt class="text-sm text-muted-foreground">Width</dt>
        <dd class="text-foreground">{{ props.frame.widthPct }} %</dd>
      </div>
      <div>
        <dt class="text-sm text-muted-foreground">Height</dt>
        <dd class="text-foreground">{{ props.frame.heightPct }} %</dd>
      </div>
    </dl>

    <div
      class="relative overflow-hidden rounded-lg border border-border bg-muted/10"
      data-testid="print-frame-preview"
    >
      <img
        v-if="props.mockupUrl"
        :src="props.mockupUrl"
        alt="Product mockup of the default colour"
        class="block w-full"
        data-testid="print-frame-mockup"
      />
      <div v-else class="aspect-square w-full" />
      <div
        class="absolute border-2 border-dashed border-primary bg-primary/10"
        :style="rectangleStyle"
        data-testid="print-frame-rectangle"
      />
    </div>
  </div>
</template>
