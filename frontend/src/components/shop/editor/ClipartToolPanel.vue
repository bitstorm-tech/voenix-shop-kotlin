<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Trash2 } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { ThumbnailButton } from '@/components/ui/thumbnail-button'
import { CLIPART_CATEGORIES, clipartUrl, type ClipartId } from '@/lib/cliparts'

defineProps<{
  hasSelection: boolean
}>()

const emit = defineEmits<{
  add: [clipartId: ClipartId]
  removeSelected: []
}>()

const { t } = useI18n()

const categories = CLIPART_CATEGORIES.map((category) => ({
  id: category.id,
  cliparts: category.cliparts.map((id) => ({ id, url: clipartUrl(id) })),
}))
</script>

<template>
  <div class="clipart-tool-panel grid gap-4" data-testid="editor-clipart-tool-panel">
    <div class="flex items-start justify-between gap-3">
      <p class="m-0 text-sm text-muted-foreground">
        {{ hasSelection ? t('editor.cliparts.selectedHint') : t('editor.cliparts.hint') }}
      </p>
      <Button
        v-if="hasSelection"
        variant="destructive"
        size="sm"
        data-testid="editor-clipart-remove"
        @click="emit('removeSelected')"
      >
        <Trash2 class="h-3.5 w-3.5" />
        {{ t('editor.cliparts.remove') }}
      </Button>
    </div>

    <section
      v-for="category in categories"
      :key="category.id"
      class="grid gap-2"
      :aria-label="t(`editor.cliparts.categories.${category.id}`)"
    >
      <h3 class="m-0 text-xs font-bold uppercase tracking-wide text-muted-foreground">
        {{ t(`editor.cliparts.categories.${category.id}`) }}
      </h3>
      <div class="flex flex-wrap gap-2">
        <ThumbnailButton
          v-for="clipart in category.cliparts"
          :key="clipart.id"
          class="size-14 cursor-pointer bg-white p-1.5 [&_img]:object-contain"
          :src="clipart.url"
          :alt="t(`editor.cliparts.options.${clipart.id}`)"
          :title="t(`editor.cliparts.options.${clipart.id}`)"
          :data-testid="`editor-clipart-option-${clipart.id}`"
          @click="emit('add', clipart.id)"
        />
      </div>
    </section>
  </div>
</template>
