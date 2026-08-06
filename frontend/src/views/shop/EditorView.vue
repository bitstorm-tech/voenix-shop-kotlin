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
  createDevEditorImageBlob,
  createDevEditorMug,
  DEV_EDITOR_ARTICLE_ID,
  DEV_EDITOR_DRAFT_ID,
  DEV_EDITOR_VARIANT_ID,
} from '@/lib/editorDevFixture'
import { useEditorStore } from '@/stores/shop/editor'
import { useMugsStore } from '@/stores/shop/mugs'

const route = useRoute()
const editorStore = useEditorStore()
const mugsStore = useMugsStore()
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

const article = computed(() => {
  const draft = routeDraft.value
  if (!draft) return null

  const mug = mugsStore.getMugById(draft.articleId)
  return mug ? toEditorArticle(mug) : null
})

const variant = computed(() => {
  const draft = routeDraft.value
  const currentArticle = article.value
  if (!draft || !currentArticle) return null

  const mug = mugsStore.getMugById(draft.articleId)
  const mugVariant = mug?.variants.find((item) => item.id === draft.variantId)
  return mugVariant ? toEditorArticleVariant(mugVariant) : null
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

    if (canUseDevelopmentFixture && draftId === DEV_EDITOR_DRAFT_ID) {
      ensureDevelopmentDraft()
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
      await mugsStore.fetchMugs()
    }
  } finally {
    if (shouldUseDevelopmentContext) {
      ensureDevelopmentMug()
    }

    hasLoadedContext.value = true
    isLoadingContext.value = false
  }
}

function ensureDevelopmentMug() {
  mugsStore.upsertMug(createDevEditorMug())
}

function ensureDevelopmentDraft() {
  ensureDevelopmentMug()

  return editorStore.ensureDevDraft({
    id: DEV_EDITOR_DRAFT_ID,
    articleId: DEV_EDITOR_ARTICLE_ID,
    variantId: DEV_EDITOR_VARIANT_ID,
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
