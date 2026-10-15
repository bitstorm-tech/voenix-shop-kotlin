<script setup lang="ts">
import { onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import { cn } from '@/lib/utils'

/**
 * The jump list beside a long admin form: one link per `AdminFormSection`, the one on screen
 * highlighted. It is the orientation a page with four or five sections needs, without hiding any
 * of them — every section stays rendered and every section is saved, the nav only scrolls.
 *
 * The highlight follows the sections through an `IntersectionObserver`; the section whose top is
 * closest below the top edge wins. Environments without the observer (the unit tests) just show the
 * list.
 */
export interface FormSectionNavItem {
  id: string
  label: string
}

const props = defineProps<{
  sections: FormSectionNavItem[]
}>()

const activeId = shallowRef<string | null>(null)
let observer: IntersectionObserver | null = null

function observeSections() {
  observer?.disconnect()
  observer = null

  if (typeof IntersectionObserver === 'undefined') {
    return
  }

  const visible = new Map<string, number>()
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          visible.set(entry.target.id, entry.boundingClientRect.top)
        } else {
          visible.delete(entry.target.id)
        }
      }

      const topmost = [...visible.entries()].sort((a, b) => a[1] - b[1])[0]
      if (topmost) {
        activeId.value = topmost[0]
      }
    },
    { rootMargin: '-10% 0px -60% 0px' },
  )

  for (const section of props.sections) {
    const element = document.getElementById(section.id)
    if (element) {
      observer.observe(element)
    }
  }
}

function jumpTo(event: Event, id: string) {
  const element = document.getElementById(id)
  if (!element) {
    return
  }

  event.preventDefault()
  element.scrollIntoView({ behavior: 'smooth', block: 'start' })
  activeId.value = id
}

onMounted(observeSections)
watch(() => props.sections.map((section) => section.id).join(','), observeSections, {
  flush: 'post',
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <nav aria-label="Sections" class="text-sm" data-testid="form-section-nav">
    <ul class="space-y-1 border-l border-border">
      <li v-for="section in sections" :key="section.id">
        <a
          :href="`#${section.id}`"
          :class="
            cn(
              '-ml-px block border-l-2 py-1 pl-3 transition-colors',
              activeId === section.id
                ? 'border-primary font-medium text-foreground'
                : 'border-transparent text-muted-foreground hover:border-border hover:text-foreground',
            )
          "
          :aria-current="activeId === section.id ? 'location' : undefined"
          @click="jumpTo($event, section.id)"
        >
          {{ section.label }}
        </a>
      </li>
    </ul>
  </nav>
</template>
