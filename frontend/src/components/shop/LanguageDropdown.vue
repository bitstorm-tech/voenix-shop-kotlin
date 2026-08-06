<script setup lang="ts">
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useLocaleStore } from '@/stores/shared/locale'

const languages = [
  { code: 'de', name: 'Deutsch', flag: '🇩🇪' },
  { code: 'en', name: 'English', flag: '🇬🇧' },
]

const localeStore = useLocaleStore()
const { locale } = useI18n()

const selectedLanguage = computed({
  get: () => localeStore.locale,
  set: (value: string) => {
    localeStore.setLocale(value)
    locale.value = value
  },
})
</script>

<template>
  <Select v-model="selectedLanguage">
    <SelectTrigger
      class="h-auto w-auto gap-1 border-0 bg-transparent px-2 py-1.5 shadow-none focus:ring-0"
    >
      <span class="text-lg">{{ languages.find((l) => l.code === selectedLanguage)?.flag }}</span>
    </SelectTrigger>
    <SelectContent>
      <SelectItem v-for="lang in languages" :key="lang.code" :value="lang.code">
        <span class="mr-2">{{ lang.flag }}</span>
        {{ lang.name }}
      </SelectItem>
    </SelectContent>
  </Select>
</template>
