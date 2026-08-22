<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import RoyalDogSectionHeader from './RoyalDogSectionHeader.vue'

defineProps<{
  /** Royal portrait renders; the section stays hidden until the campaign assets exist. */
  images: string[]
}>()

const { t } = useI18n()
</script>

<template>
  <section v-if="images.length > 0" class="py-14">
    <div class="mx-auto max-w-[1100px] px-6">
      <RoyalDogSectionHeader
        :kicker="t('royalDog.gallery.kicker')"
        :title="t('royalDog.gallery.title')"
      />
      <div class="grid grid-cols-2 gap-4 md:grid-cols-4">
        <div v-for="image in images" :key="image" class="gallery-frame overflow-hidden">
          <img
            :src="image"
            :alt="t('royalDog.gallery.alt')"
            class="block aspect-square size-full rounded-[0.3rem] object-cover"
            width="400"
            height="400"
            loading="lazy"
            decoding="async"
          />
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* CSS exception: thin gilded frames echoing the hero portrait frame. */
.gallery-frame {
  padding: 0.35rem;
  border-radius: 0.55rem;
  background: linear-gradient(150deg, #ecd08a, #b8873b 40%, #f4e0a4 58%, #a97c2e);
  box-shadow:
    0 10px 24px rgba(24, 20, 40, 0.16),
    inset 0 1px 0 rgba(255, 255, 255, 0.5);
}
</style>
