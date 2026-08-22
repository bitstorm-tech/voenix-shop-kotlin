<script setup lang="ts">
import type { ArticleSubcategoryDto, CategoryDto } from '@/stores/shop/articleCategories'
import { ArrowRight } from 'lucide-vue-next'
import { NavigationMenuLink } from '@/components/ui/navigation-menu'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, type RouteLocationRaw } from 'vue-router'
import HeaderSubcategoryMenuCard from './HeaderSubcategoryMenuCard.vue'

const props = defineProps<{
  category: CategoryDto
}>()

const { t } = useI18n()

const subcategories = computed(() => props.category.subcategories)
const categoryLabel = computed(() => props.category.name)

const categoryRoute = computed<RouteLocationRaw>(() => ({
  name: 'products',
  query: { category: props.category.id.toString() },
}))

function subcategoryRoute(subcategoryId: number): RouteLocationRaw {
  return {
    name: 'products',
    query: {
      category: props.category.id.toString(),
      subcategory: subcategoryId.toString(),
    },
  }
}

function subcategoryLabel(subcategory: ArticleSubcategoryDto) {
  return subcategory.name
}

function imageAlt(label: string) {
  return t('header.subcategoryImageAlt', { subcategory: label })
}

function subcategoryImage(subcategory: ArticleSubcategoryDto): string | null {
  if (!subcategory.exampleImageFilename) {
    return null
  }

  return `/api/images/public/400/articles/subcategory-example-images/${subcategory.exampleImageFilename}`
}

const subcategoryCards = computed(() =>
  subcategories.value.map((subcategory) => {
    const title = subcategoryLabel(subcategory)

    return {
      id: subcategory.id,
      to: subcategoryRoute(subcategory.id),
      title,
      imageSrc: subcategoryImage(subcategory),
      imageAlt: imageAlt(title),
    }
  }),
)
</script>

<template>
  <div
    class="w-screen bg-background-soft [--super-menu-panel-card-width:10.75rem] max-[48rem]:[--super-menu-panel-card-width:10rem]"
    :aria-label="categoryLabel"
  >
    <div
      class="mx-auto grid w-[calc(100vw-3rem)] gap-4 py-[1.15rem] pb-[1.3rem] max-[72rem]:w-[calc(100vw-2rem)]"
    >
      <div
        v-if="subcategoryCards.length > 0"
        class="grid content-start items-start justify-center gap-[0.8rem] grid-cols-[repeat(auto-fit,minmax(9.75rem,var(--super-menu-panel-card-width)))] max-[72rem]:grid-cols-[repeat(auto-fit,minmax(9.25rem,var(--super-menu-panel-card-width)))]"
      >
        <HeaderSubcategoryMenuCard
          v-for="card in subcategoryCards"
          :key="card.id"
          :to="card.to"
          :title="card.title"
          :image-src="card.imageSrc"
          :image-alt="card.imageAlt"
        />
      </div>

      <NavigationMenuLink v-else as-child>
        <RouterLink
          class="group inline-flex min-h-[2.75rem] w-fit items-center gap-[0.45rem] self-start rounded-lg border border-[oklch(0_0_0_/_0.08)] bg-[oklch(1_0_0_/_0.78)] px-[0.9rem] py-[0.65rem] text-[0.9rem] font-[820] text-foreground no-underline shadow-[0_10px_28px_oklch(0_0_0_/_0.07)] transition-[border-color,box-shadow,transform] duration-[180ms] ease-[ease] hover:-translate-y-0.5 hover:border-[oklch(0.61_0.19_35_/_0.34)] hover:shadow-[0_16px_36px_oklch(0_0_0_/_0.1)] focus-visible:outline-[2px_solid_var(--primary)] focus-visible:outline-offset-[3px] motion-reduce:transition-none motion-reduce:hover:translate-y-0 dark:border-[oklch(1_0_0_/_0.1)] dark:bg-[oklch(0.24_0.012_255_/_0.76)] dark:shadow-[0_18px_38px_oklch(0_0_0_/_0.3)] dark:hover:border-[oklch(0.72_0.17_35_/_0.45)]"
          :to="categoryRoute"
        >
          {{ t('header.allCategory', { category: categoryLabel }) }}
          <ArrowRight
            class="size-[0.95rem] flex-none transition-transform duration-[180ms] ease-[ease] group-hover:translate-x-0.5 motion-reduce:transition-none motion-reduce:group-hover:translate-x-0"
            aria-hidden="true"
          />
        </RouterLink>
      </NavigationMenuLink>
    </div>
  </div>
</template>
