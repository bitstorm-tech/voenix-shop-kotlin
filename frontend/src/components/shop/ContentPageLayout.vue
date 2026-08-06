<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { Button } from '@/components/ui/button'

interface Props {
  eyebrow: string
  title: string
  description: string
  note: string
  primaryActionLabel: string
  secondaryActionLabel: string
}

defineProps<Props>()
</script>

<template>
  <section class="bg-background">
    <div class="mx-auto flex min-h-[calc(100vh-18rem)] max-w-5xl items-center px-6 py-16 sm:px-8">
      <div
        class="w-full rounded-lg border border-border bg-background-soft p-8 shadow-sm sm:p-10 lg:p-14"
      >
        <span
          class="inline-flex rounded-sm bg-primary/10 px-3 py-1 text-xs font-semibold uppercase tracking-[0.2em] text-primary"
        >
          {{ eyebrow }}
        </span>

        <div class="mt-6 max-w-3xl space-y-4">
          <h1 class="text-4xl font-semibold text-foreground sm:text-5xl">
            {{ title }}
          </h1>
          <p class="text-muted-foreground text-lg leading-8">
            {{ description }}
          </p>
          <p class="text-muted-foreground/90 max-w-2xl text-sm leading-7">
            {{ note }}
          </p>
        </div>

        <div class="mt-8 flex flex-col gap-3 sm:flex-row">
          <Button as-child size="lg">
            <RouterLink :to="{ name: 'home' }">
              {{ primaryActionLabel }}
            </RouterLink>
          </Button>
          <Button as-child variant="outline" size="lg">
            <RouterLink :to="{ name: 'mugs' }">
              {{ secondaryActionLabel }}
            </RouterLink>
          </Button>
        </div>
      </div>
    </div>
  </section>
</template>
