<script setup lang="ts">
import { computed, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import { isRouteActive } from '@/lib/routeMatching'
import { cn } from '@/lib/utils'
import {
  getAdminNavLinkActivePatterns,
  type AdminNavGroupItem,
  type AdminNavItem,
  type AdminNavLinkItem,
} from './adminNavigation'

interface Props {
  items: AdminNavItem[]
}

const props = defineProps<Props>()

const emit = defineEmits<{
  navigate: []
}>()

const route = useRoute()
const { t } = useI18n()
const navigationId = useId()

const entries = computed(() => props.items)

function isLinkActive(item: AdminNavLinkItem) {
  return isRouteActive(route.path, getAdminNavLinkActivePatterns(item))
}

function isGroupActive(item: AdminNavGroupItem) {
  return item.children.some((child) => isLinkActive(child))
}

function getNavGroupHeadingId(item: AdminNavGroupItem) {
  const groupSlug = item.title.toLowerCase().replace(/[^a-z0-9]+/g, '-')

  return `admin-nav-${navigationId}-group-${groupSlug}`
}

function handleNavigate() {
  emit('navigate')
}

function getItemTitle(item: { title: string; titleKey?: string }) {
  return item.titleKey ? t(item.titleKey) : item.title
}
</script>

<template>
  <nav class="space-y-1.5" :aria-label="t('admin.navigation.label')">
    <template v-for="item in entries" :key="item.type === 'link' ? item.to : item.title">
      <RouterLink
        v-if="item.type === 'link'"
        :to="item.to"
        :aria-current="isLinkActive(item) ? 'page' : undefined"
        :class="
          cn(
            'group flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors duration-200',
            isLinkActive(item)
              ? 'bg-primary/10 text-primary'
              : 'text-muted-foreground hover:bg-muted hover:text-foreground',
          )
        "
        :data-nav-title="getItemTitle(item)"
        data-nav-link
        @click="handleNavigate"
      >
        <span
          v-if="item.icon"
          :class="
            cn(
              'flex size-8 shrink-0 items-center justify-center rounded-md transition-colors duration-200',
              isLinkActive(item)
                ? 'bg-primary/12 text-primary'
                : 'bg-background text-muted-foreground group-hover:text-foreground',
            )
          "
          data-nav-icon
        >
          <component :is="item.icon" class="size-4" />
        </span>

        <span class="truncate">{{ getItemTitle(item) }}</span>
      </RouterLink>

      <section
        v-else
        class="space-y-1"
        role="group"
        :aria-labelledby="getNavGroupHeadingId(item)"
        data-nav-group
      >
        <div
          :id="getNavGroupHeadingId(item)"
          :class="
            cn(
              'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-semibold transition-colors duration-200',
              isGroupActive(item) ? 'bg-primary/5 text-primary' : 'text-muted-foreground',
            )
          "
          :data-active="isGroupActive(item) ? 'true' : undefined"
          :data-nav-title="getItemTitle(item)"
          data-nav-group-heading
        >
          <span
            :class="
              cn(
                'flex size-8 shrink-0 items-center justify-center rounded-md transition-colors duration-200',
                isGroupActive(item)
                  ? 'bg-primary/12 text-primary'
                  : 'bg-background text-muted-foreground',
              )
            "
            data-nav-icon
          >
            <component :is="item.icon" class="size-4" />
          </span>

          <span class="truncate">{{ getItemTitle(item) }}</span>
        </div>

        <div class="space-y-1 pl-11" data-nav-children>
          <RouterLink
            v-for="child in item.children"
            :key="child.to"
            :to="child.to"
            :aria-current="isLinkActive(child) ? 'page' : undefined"
            :class="
              cn(
                'block rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200',
                isLinkActive(child)
                  ? 'bg-primary/10 text-primary'
                  : 'text-muted-foreground hover:bg-muted hover:text-foreground',
              )
            "
            :data-nav-title="getItemTitle(child)"
            data-nav-child-link
            data-nav-link
            @click="handleNavigate"
          >
            <span class="truncate">{{ getItemTitle(child) }}</span>
          </RouterLink>
        </div>
      </section>
    </template>
  </nav>
</template>
