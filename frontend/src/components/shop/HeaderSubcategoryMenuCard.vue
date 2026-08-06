<script setup lang="ts">
import { NavigationMenuLink } from '@/components/ui/navigation-menu'
import { RouterLink, type RouteLocationRaw } from 'vue-router'

defineProps<{
  to: RouteLocationRaw
  title: string
  imageSrc: string | null
  imageAlt: string
}>()
</script>

<template>
  <NavigationMenuLink as-child>
    <RouterLink
      class="group flex min-w-0 flex-col overflow-hidden rounded-lg border border-[oklch(0_0_0_/_0.08)] bg-[oklch(1_0_0_/_0.78)] text-inherit no-underline shadow-[0_10px_28px_oklch(0_0_0_/_0.07)] transition-[border-color,box-shadow,transform] duration-[180ms] ease-[ease] hover:-translate-y-0.5 hover:border-[oklch(0.61_0.19_35_/_0.34)] hover:shadow-[0_16px_36px_oklch(0_0_0_/_0.1)] focus-visible:outline-[2px_solid_var(--primary)] focus-visible:outline-offset-[3px] motion-reduce:transition-none motion-reduce:hover:translate-y-0 dark:border-[oklch(1_0_0_/_0.1)] dark:bg-[oklch(0.24_0.012_255_/_0.76)] dark:shadow-[0_18px_38px_oklch(0_0_0_/_0.3)] dark:hover:border-[oklch(0.72_0.17_35_/_0.45)]"
      :to="to"
      data-testid="super-menu-subcategory"
    >
      <span
        class="relative grid aspect-[1.18] place-items-center overflow-hidden bg-muted"
        :class="
          !imageSrc &&
          'border-b border-dashed border-[oklch(0_0_0_/_0.14)] bg-[linear-gradient(135deg,oklch(0.96_0.018_78),oklch(0.94_0.012_245)),var(--muted)] dark:border-[oklch(1_0_0_/_0.14)] dark:bg-[linear-gradient(135deg,oklch(0.29_0.018_78),oklch(0.24_0.018_245)),var(--muted)]'
        "
      >
        <img
          v-if="imageSrc"
          class="block size-full object-cover transition-transform duration-[260ms] ease-[ease] group-hover:scale-[1.045] motion-reduce:transition-none motion-reduce:group-hover:scale-100"
          :src="imageSrc"
          :alt="imageAlt"
          width="172"
          height="146"
          loading="lazy"
          decoding="async"
        />
        <span
          v-else
          class="inline-flex min-h-8 min-w-[6.5rem] items-center justify-center rounded-lg border border-[oklch(0_0_0_/_0.16)] bg-[oklch(1_0_0_/_0.62)] text-[0.72rem] font-extrabold text-muted-foreground uppercase dark:border-[oklch(1_0_0_/_0.16)] dark:bg-[oklch(0.18_0.012_255_/_0.7)]"
          aria-hidden="true"
        >
          No image
        </span>
      </span>
      <span
        class="line-clamp-2 min-h-12 overflow-hidden px-[0.72rem] py-[0.68rem] text-[0.84rem] leading-[1.18] font-extrabold text-foreground"
      >
        {{ title }}
      </span>
    </RouterLink>
  </NavigationMenuLink>
</template>
