<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Star } from 'lucide-vue-next'
import { Card } from '@/components/ui/card'
import RoyalDogSectionHeader from './RoyalDogSectionHeader.vue'

const { t } = useI18n()

const reviews = ['lena', 'tom', 'anna'] as const
</script>

<template>
  <section class="bg-background-soft py-14">
    <div class="mx-auto max-w-[1100px] px-6">
      <RoyalDogSectionHeader
        :kicker="t('royalDog.reviews.kicker')"
        :title="t('royalDog.reviews.title')"
      />
      <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card v-for="review in reviews" :key="review" as="article" class="rounded-lg bg-card p-5">
          <div class="mb-2 flex gap-0.5 text-rating">
            <Star v-for="index in 5" :key="index" class="size-3.5 fill-current" />
          </div>
          <p class="text-sm leading-relaxed text-foreground-soft">
            {{ t(`royalDog.reviews.items.${review}.text`) }}
          </p>
          <div class="mt-4 flex items-center gap-2">
            <div
              class="grid size-7 place-items-center rounded-full bg-background-warm text-xs font-bold text-foreground-soft"
            >
              {{ t(`royalDog.reviews.items.${review}.initial`) }}
            </div>
            <div>
              <p class="text-xs font-bold text-foreground">
                {{ t(`royalDog.reviews.items.${review}.name`) }}
              </p>
              <p class="text-[11px] text-foreground-faint">
                {{ t(`royalDog.reviews.items.${review}.detail`) }}
              </p>
            </div>
          </div>
        </Card>
      </div>
    </div>
  </section>
</template>
