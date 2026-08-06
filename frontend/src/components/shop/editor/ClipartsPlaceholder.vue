<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Sticker } from 'lucide-vue-next'

const { t } = useI18n()
</script>

<template>
  <div
    class="mt-3 flex items-start gap-3 rounded-xl border border-dashed border-border bg-[oklch(0.97_0.004_80_/_0.72)] p-3.5 text-muted-foreground"
    data-testid="editor-cliparts-placeholder"
    aria-disabled="true"
  >
    <Sticker class="size-5" aria-hidden="true" />
    <div>
      <p class="m-0 text-[0.9rem] font-bold text-foreground">
        {{ t('editor.cliparts.title') }}
      </p>
      <p class="m-0 mt-1 text-[0.82rem] leading-[1.45]">
        {{ t('editor.cliparts.body') }}
      </p>
    </div>
  </div>
</template>
