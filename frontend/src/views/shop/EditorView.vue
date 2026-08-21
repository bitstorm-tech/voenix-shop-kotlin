<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  EditorStateMessage,
  ProductEditor,
  toEditorArticle,
  toEditorArticleVariant,
} from '@/components/shop/editor'
import {
  createDevEditorArticles,
  createDevEditorImageBlob,
  findDevEditorDraftFixture,
} from '@/lib/editorDevFixture'
import { useEditorStore } from '@/stores/shop/editor'
import { useCatalogStore } from '@/stores/shop/catalog'

const route = useRoute()
const editorStore = useEditorStore()
const catalogStore = useCatalogStore()
const canUseDevelopmentFixture = import.meta.env.MODE === 'development'

type EditorRouteState = 'guard' | 'missing' | 'invalid' | 'loading' | 'ready'
type EditorMessageState = Exclude<EditorRouteState, 'ready'>

const isLoadingContext = shallowRef(false)
const hasLoadedContext = shallowRef(false)

const routeDraftId = computed(() => {
  const rawDraftId = route.params.draftId
  const draftId = Array.isArray(rawDraftId) ? rawDraftId[0] : rawDraftId

  return typeof draftId === 'string' && draftId.length > 0 ? draftId : null
})

const routeDraft = computed(() => {
  const draftId = routeDraftId.value
  if (!draftId) return null

  return editorStore.drafts.find((draft) => draft.id === draftId) ?? null
})

const catalogArticle = computed(() => {
  const draft = routeDraft.value
  if (!draft) return null

  return catalogStore.getArticleById(draft.articleId) ?? null
})

const article = computed(() => {
  const shopArticle = catalogArticle.value
  return shopArticle ? toEditorArticle(shopArticle) : null
})

const variant = computed(() => {
  const draft = routeDraft.value
  const shopArticle = catalogArticle.value
  if (!draft || !shopArticle) return null

  return toEditorArticleVariant(shopArticle, draft.variantId)
})

const readyModel = computed(() => {
  const draft = routeDraft.value
  const currentArticle = article.value
  const currentVariant = variant.value
  if (!draft || !currentArticle || !currentVariant) return null

  return {
    draft,
    article: currentArticle,
    variant: currentVariant,
  }
})

const routeState = computed<EditorRouteState>(() => {
  if (!routeDraftId.value) return 'guard'
  if (!routeDraft.value) return 'missing'
  if (isLoadingContext.value || !hasLoadedContext.value) return 'loading'
  if (!readyModel.value) return 'invalid'

  return 'ready'
})

const stateMessageState = computed<EditorMessageState | null>(() =>
  routeState.value === 'ready' ? null : routeState.value,
)

watch(
  routeDraftId,
  (draftId) => {
    editorStore.selectDraft(draftId)

    if (!draftId) return

    if (canUseDevelopmentFixture && findDevEditorDraftFixture(draftId) !== null) {
      ensureDevelopmentDraft(draftId)
      void loadContext()
      return
    }

    void loadContext()
  },
  { immediate: true },
)

async function loadContext() {
  if (isLoadingContext.value) return

  const shouldUseDevelopmentContext =
    canUseDevelopmentFixture && routeDraft.value?.source === 'dev-fixture'

  isLoadingContext.value = true
  try {
    if (!shouldUseDevelopmentContext) {
      await catalogStore.fetchArticles()
    }
  } finally {
    if (shouldUseDevelopmentContext) {
      ensureDevelopmentArticles()
    }

    hasLoadedContext.value = true
    isLoadingContext.value = false
  }
}

function ensureDevelopmentArticles() {
  for (const devArticle of createDevEditorArticles()) {
    catalogStore.upsertArticle(devArticle)
  }
}

function ensureDevelopmentDraft(draftId: string) {
  const fixture = findDevEditorDraftFixture(draftId)
  if (!fixture) return null

  ensureDevelopmentArticles()

  return editorStore.ensureDevDraft({
    ...fixture,
    imageBlob: createDevEditorImageBlob(),
  })
}
</script>

<template>
  <section class="mx-auto w-full max-w-[96rem] px-4 pb-16 pt-4 md:px-6 md:pb-20 md:pt-6">
    <ProductEditor
      v-if="routeState === 'ready' && readyModel"
      :draft="readyModel.draft"
      :article="readyModel.article"
      :variant="readyModel.variant"
    />
    <EditorStateMessage v-else-if="stateMessageState" :state="stateMessageState" />
  </section>
</template>
