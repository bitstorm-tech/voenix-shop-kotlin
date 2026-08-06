<script setup lang="ts">
import { Image as ImageIcon } from 'lucide-vue-next'
import { computed, shallowRef, watch } from 'vue'
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui/hover-card'

interface Props {
  filename?: string | null
  title: string
  imageUrl: (filename: string, size: number) => string
}

const props = defineProps<Props>()

const imageFailed = shallowRef(false)

watch(
  () => props.filename,
  () => {
    imageFailed.value = false
  },
)

const imageUrls = computed(() =>
  props.filename && !imageFailed.value
    ? {
        thumbnail: props.imageUrl(props.filename, 80),
        preview: props.imageUrl(props.filename, 400),
      }
    : null,
)
</script>

<template>
  <HoverCard v-if="imageUrls" :open-delay="200">
    <HoverCardTrigger as-child>
      <img
        :src="imageUrls.thumbnail"
        :alt="title"
        class="size-10 shrink-0 rounded-md border border-border bg-muted/20 object-cover"
        data-testid="example-image-thumbnail"
        @error="imageFailed = true"
      />
    </HoverCardTrigger>
    <HoverCardContent class="w-auto p-2">
      <img
        :src="imageUrls.preview"
        :alt="title"
        class="w-50 rounded-md"
        data-testid="example-image-hover-preview"
      />
    </HoverCardContent>
  </HoverCard>
  <div
    v-else
    class="flex size-10 shrink-0 items-center justify-center rounded-md border border-dashed border-border bg-muted/20 text-muted-foreground"
    data-testid="example-image-placeholder"
  >
    <ImageIcon class="size-4" aria-hidden="true" />
  </div>
</template>
