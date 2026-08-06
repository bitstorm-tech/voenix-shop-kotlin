<script setup lang="ts">
import { computed } from 'vue'
import { Monitor, Moon, Sun } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'
import { useThemeStore, type Theme } from '@/stores/shared/theme'

const themes: { value: Theme; icon: typeof Sun }[] = [
  { value: 'light', icon: Sun },
  { value: 'dark', icon: Moon },
  { value: 'system', icon: Monitor },
]

const { t } = useI18n()
const themeStore = useThemeStore()

const selectedTheme = computed({
  get: () => themeStore.theme,
  set: (value: Theme) => themeStore.setTheme(value),
})

const currentIcon = computed(
  () => themes.find((option) => option.value === selectedTheme.value)?.icon ?? Sun,
)
</script>

<template>
  <Select v-model="selectedTheme">
    <SelectTrigger
      class="h-auto w-auto gap-1 border-0 bg-transparent px-2 py-1.5 shadow-none focus:ring-0"
      :aria-label="t('header.toggleTheme')"
    >
      <component :is="currentIcon" class="size-4" />
      <span class="sr-only">{{ t(`mobileMenu.themes.${selectedTheme}`) }}</span>
    </SelectTrigger>
    <SelectContent>
      <SelectItem v-for="option in themes" :key="option.value" :value="option.value">
        <span class="flex items-center gap-2">
          <component :is="option.icon" class="size-4" />
          {{ t(`mobileMenu.themes.${option.value}`) }}
        </span>
      </SelectItem>
    </SelectContent>
  </Select>
</template>
