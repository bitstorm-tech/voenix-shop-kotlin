<script setup lang="ts">
import { ArrowLeft, ChevronDown, Trash2 } from 'lucide-vue-next'
import { computed, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AdminArticlePrintFrameViewer from '@/components/admin/article/AdminArticlePrintFrameViewer.vue'
import AdminArticleSpodVariantTable from '@/components/admin/article/AdminArticleSpodVariantTable.vue'
import AdminArticlePriceSection from '@/components/admin/pricing/AdminArticlePriceSection.vue'
import AdminFormSection from '@/components/admin/shared/AdminFormSection.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useAdminArticleEditor } from '@/composables/useAdminArticleEditor'
import { NONE_VALUE, useAdminArticleGeneralForm } from '@/composables/useAdminArticleGeneralForm'
import { useAdminPriceForm } from '@/composables/useAdminPriceForm'
import { hasFormErrors, mapSaveErrors, SPOD_SPEC } from '@/lib/adminArticleErrors'
import { formatAdminStamp } from '@/lib/adminStamp'
import { sizeChartImageUrl, spodMockupImageUrl } from '@/lib/variantExampleImage'
import type { InvalidArticleRequestError } from '@/stores/admin/articles'
import {
  type AdminSpodArticleDto,
  type AdminSpodArticleSyncDto,
  type AdminSpodVariantDto,
  type SaveAdminSpodArticleRequest,
  useAdminSpodArticlesStore,
} from '@/stores/admin/spodArticles'

/**
 * The SPOD article editor. A SPOD article has two owners, and so has this screen: the Print and
 * Spreadconnect sections *show* what a sync run wrote — the measured print frame, the name, the
 * descriptions, the variants, the size chart — and the other sections edit what the shop decides
 * about it.
 *
 * There is no create mode, no variant editing, and no frame editing, because an article comes into
 * being through a sync run, its variants are the partner's, and its print frame is measured by the
 * sync. What the form submits is exactly the shop-owned half.
 */

interface ShopFormState {
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  defaultVariantId: number | null
}

/**
 * The messages the form shows on its own inputs. The keys are the ones the backend uses on the JSON
 * paths of a rejected write (`lib/adminArticleErrors.ts` folds the paths onto them), so a
 * client-side rule and a server-side rejection land in the same place.
 */
const FIELD_ERROR_KEYS = [
  'active',
  'categoryId',
  'subcategoryId',
  'defaultVariantId',
  'price',
] as const

type FieldErrorKey = (typeof FIELD_ERROR_KEYS)[number]
type FieldErrors = Partial<Record<FieldErrorKey, string>>

const articlesStore = useAdminSpodArticlesStore()
const articlePrice = useAdminPriceForm({ persistence: 'optional' })

const shop = reactive<ShopFormState>({
  active: false,
  categoryId: null,
  subcategoryId: null,
  defaultVariantId: null,
})

/** The partner-owned half of the loaded article. It is shown, never edited. */
const synced = ref<AdminSpodArticleDto | null>(null)
const showInactiveVariants = ref(false)

const fieldErrors = reactive<FieldErrors>({})

const {
  listLocation,
  generalError,
  isLoading,
  isSaving,
  isDeleting,
  isDeleteDialogOpen,
  priceVatOptions,
  categoriesStore,
  saveArticle,
  deleteCurrentArticle,
} = useAdminArticleEditor({
  articlesStore,
  listRoute: 'admin-spod-articles',
  articlePrice,
  resetForm,
  fillForm,
  clearErrors,
  validate,
  buildPayload,
  applySaveErrors,
  showPriceRequired: () => {
    fieldErrors.price = 'An active article requires a price.'
  },
  articleName: () => synced.value?.name ?? '',
})

const { filteredSubcategories, categorySelectValue, subcategorySelectValue } =
  useAdminArticleGeneralForm(shop)

const pageTitle = computed(() => {
  const articleName = synced.value?.name.trim() ?? ''
  return articleName === '' ? 'Edit SPOD article' : `Edit SPOD article (${articleName})`
})

const variants = computed<AdminSpodVariantDto[]>(() => synced.value?.spodVariants ?? [])
const appearances = computed(() => synced.value?.appearances ?? [])

/** Only an active variant may be the one a customer sees first, so only those are offered. */
const activeVariants = computed(() => variants.value.filter((variant) => variant.active))
const inactiveVariants = computed(() => variants.value.filter((variant) => !variant.active))

const defaultVariantSelectValue = computed({
  get: () => shop.defaultVariantId?.toString() ?? NONE_VALUE,
  set: (value: string) => {
    shop.defaultVariantId = value === NONE_VALUE ? null : Number(value)
  },
})

const sync = computed<AdminSpodArticleSyncDto | null>(() => synced.value?.sync ?? null)

const isMissingAtSpreadconnect = computed(() => Boolean(sync.value?.missingSince))

/**
 * The photo the frame is drawn on: the mockup of the default variant's colour, the same picture the
 * shop editor uses as the backdrop of its preview. Without one the viewer shows a blank square.
 */
const mockupUrl = computed(() => {
  const defaultAppearanceId = variants.value.find(
    (variant) => variant.id === shop.defaultVariantId,
  )?.appearanceId
  const filename = appearances.value.find(
    (appearance) => appearance.id === defaultAppearanceId,
  )?.mockupImageFilename

  return filename ? spodMockupImageUrl(filename, 1000) : null
})

const sizeChartUrl = computed(() =>
  synced.value?.sizeChartImageFilename
    ? sizeChartImageUrl(synced.value.sizeChartImageFilename, 400)
    : null,
)

function resetForm() {
  shop.active = false
  shop.categoryId = null
  shop.subcategoryId = null
  shop.defaultVariantId = null
  synced.value = null
  showInactiveVariants.value = false
}

function fillForm(article: AdminSpodArticleDto) {
  shop.active = article.active
  shop.categoryId = article.categoryId
  shop.subcategoryId = article.subcategoryId
  // Only an active variant is offered as the default one, so only an active one may be preselected:
  // a sync run may have deactivated the stored default, and the select would then hold an id that
  // is not among its options — and submit it unchanged.
  shop.defaultVariantId =
    article.spodVariants.find((variant) => variant.isDefault && variant.active)?.id ?? null
  synced.value = article
}

function clearErrors() {
  for (const key of FIELD_ERROR_KEYS) {
    fieldErrors[key] = undefined
  }
}

/**
 * Shows a rejected write where it belongs. Every problem of a SPOD article write is a field error on the
 * JSON path of the value that caused it, so the messages go onto the inputs instead of into one
 * anonymous alert.
 */
function applySaveErrors(error: InvalidArticleRequestError) {
  const saveErrors = mapSaveErrors(error.fieldErrors, SPOD_SPEC)

  for (const key of FIELD_ERROR_KEYS) {
    fieldErrors[key] = saveErrors.fields[key]
  }

  return saveErrors.other[0] ?? (hasFormErrors(saveErrors) ? null : error.message)
}

function validate(): boolean {
  clearErrors()

  // The database refuses an active article without a category, and the write refuses one without an
  // active default variant or one the partner no longer lists. The form does not let a user get
  // there; an inactive article may stay unsorted. An unmeasured print frame is the
  // backend's to refuse: its message lands on the Active field.
  if (shop.active) {
    if (shop.categoryId === null) {
      fieldErrors.categoryId = 'An active article requires a category.'
    }
    if (shop.defaultVariantId === null) {
      fieldErrors.defaultVariantId = 'An active article requires an active default variant.'
    }
    if (isMissingAtSpreadconnect.value) {
      fieldErrors.active = 'An article that is missing at Spreadconnect cannot be activated.'
    }
  }

  return FIELD_ERROR_KEYS.every((key) => fieldErrors[key] === undefined)
}

function buildPayload(): SaveAdminSpodArticleRequest {
  const payload: SaveAdminSpodArticleRequest = {
    active: shop.active,
    categoryId: shop.categoryId,
    subcategoryId: shop.subcategoryId,
    defaultVariantId: shop.defaultVariantId,
  }

  const pricePayload = articlePrice.getSavePayload()
  if (pricePayload !== undefined) {
    payload.price = pricePayload
  }

  return payload
}
</script>

<template>
  <section class="max-w-5xl space-y-4">
    <AdminPageHeader :title="pageTitle">
      <template #actions>
        <Button as-child variant="outline" size="sm" class="self-start">
          <RouterLink :to="listLocation">
            <ArrowLeft class="size-4" />
            Back to Articles
          </RouterLink>
        </Button>
      </template>
    </AdminPageHeader>

    <Card v-if="isLoading" class="px-4 py-12 text-center text-sm text-muted-foreground">
      Loading article...
    </Card>

    <form v-else class="space-y-4" @submit.prevent="saveArticle">
      <Alert v-if="generalError" variant="destructive" data-form-error>
        {{ generalError }}
      </Alert>

      <Alert v-if="isMissingAtSpreadconnect" variant="destructive" data-testid="missing-alert">
        Spreadconnect no longer lists this article. It was deactivated and cannot be activated again
        until a sync run finds it.
      </Alert>

      <AdminFormSection
        title="General"
        description="How the shop sorts this article and which variant a customer sees first."
      >
        <div data-testid="article-identity">
          <p class="font-semibold text-foreground">{{ synced?.name }}</p>
          <p class="text-sm text-muted-foreground">{{ synced?.productTypeName }}</p>
        </div>

        <div class="grid gap-4 md:grid-cols-2">
          <FormField label="Category" for="article-category" :error="fieldErrors.categoryId">
            <Select v-model="categorySelectValue">
              <SelectTrigger id="article-category">
                <SelectValue placeholder="Select category" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem :value="NONE_VALUE">No category</SelectItem>
                <SelectItem
                  v-for="category in categoriesStore.categories"
                  :key="category.id"
                  :value="category.id.toString()"
                >
                  {{ category.name }}{{ category.active ? '' : ' (Inactive)' }}
                </SelectItem>
              </SelectContent>
            </Select>
          </FormField>

          <FormField
            label="Subcategory"
            for="article-subcategory"
            :error="fieldErrors.subcategoryId"
            :hint="shop.categoryId === null ? 'Select a category first.' : undefined"
          >
            <Select v-model="subcategorySelectValue" :disabled="shop.categoryId === null">
              <SelectTrigger id="article-subcategory">
                <SelectValue placeholder="Select subcategory" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem :value="NONE_VALUE">No subcategory</SelectItem>
                <SelectItem
                  v-for="subcategory in filteredSubcategories"
                  :key="subcategory.id"
                  :value="subcategory.id.toString()"
                >
                  {{ subcategory.name }}{{ subcategory.active ? '' : ' (Inactive)' }}
                </SelectItem>
              </SelectContent>
            </Select>
          </FormField>
        </div>

        <FormField
          label="Default variant"
          for="article-default-variant"
          :error="fieldErrors.defaultVariantId"
          hint="The colour and size a customer sees first. Only active variants can be picked."
        >
          <Select v-model="defaultVariantSelectValue">
            <SelectTrigger id="article-default-variant" data-testid="default-variant-select">
              <SelectValue placeholder="Select default variant" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem :value="NONE_VALUE">No default variant</SelectItem>
              <SelectItem
                v-for="variant in activeVariants"
                :key="variant.id"
                :value="variant.id.toString()"
              >
                {{ variant.name }}
              </SelectItem>
            </SelectContent>
          </Select>
        </FormField>

        <div class="flex items-center gap-3 border-t border-border pt-5">
          <Checkbox id="article-active" v-model="shop.active" />
          <div>
            <Label for="article-active">Active</Label>
            <p class="text-sm text-muted-foreground">
              Active articles are visible in the shop. Requires a category, a price, an active
              default variant, and a measured print frame.
            </p>
            <p v-if="fieldErrors.active" class="text-sm text-destructive" data-form-error>
              {{ fieldErrors.active }}
            </p>
          </div>
        </div>
      </AdminFormSection>

      <AdminArticlePriceSection
        :article-price="articlePrice"
        :vat-options="priceVatOptions"
        :error="fieldErrors.price"
      />

      <AdminFormSection
        title="Print"
        description="Where the customer's design sits on the product, as the last sync measured it from the Spreadconnect hotspot. Drawn on the mockup of the default variant's colour."
        read-only
        data-testid="print-section"
      >
        <AdminArticlePrintFrameViewer
          :frame="synced?.printFrame ?? null"
          :hotspot="synced?.hotspot ?? null"
          :mockup-url="mockupUrl"
        />
      </AdminFormSection>

      <AdminFormSection
        title="Spreadconnect"
        description="Everything in this section belongs to the Spreadconnect backoffice and is overwritten by the next sync run. Change it over there, not here."
        read-only
        data-testid="spod-section"
      >
        <dl v-if="sync" class="grid gap-4 md:grid-cols-2" data-testid="spod-identity">
          <div>
            <dt class="text-sm text-muted-foreground">Name</dt>
            <dd class="text-foreground">{{ synced?.name }}</dd>
          </div>
          <div>
            <dt class="text-sm text-muted-foreground">Article ID</dt>
            <dd class="text-foreground">{{ sync.spodArticleId }}</dd>
          </div>
          <div>
            <dt class="text-sm text-muted-foreground">Environment</dt>
            <dd class="text-foreground">{{ sync.environment }}</dd>
          </div>
          <div>
            <dt class="text-sm text-muted-foreground">Last synced</dt>
            <dd class="text-foreground" data-testid="spod-synced-at">
              {{ formatAdminStamp(sync.syncedAt) }}
            </dd>
          </div>
          <div v-if="sync.missingSince">
            <dt class="text-sm text-muted-foreground">Missing since</dt>
            <dd>
              <Badge variant="warning" data-testid="spod-missing-badge">
                Missing at Spreadconnect since {{ formatAdminStamp(sync.missingSince) }}
              </Badge>
            </dd>
          </div>
          <div>
            <dt class="text-sm text-muted-foreground">Short description</dt>
            <dd class="text-foreground">{{ synced?.descriptionShort }}</dd>
          </div>
          <div>
            <dt class="text-sm text-muted-foreground">Long description</dt>
            <dd class="whitespace-pre-line text-foreground">{{ synced?.descriptionLong }}</dd>
          </div>
        </dl>

        <div class="space-y-3 border-t border-border pt-5">
          <h3 class="text-base font-semibold text-foreground">Variants</h3>
          <AdminArticleSpodVariantTable
            v-if="activeVariants.length > 0"
            :variants="activeVariants"
            :appearances="appearances"
          />
          <p v-else class="text-sm text-muted-foreground">
            This article has no active variant. The last sync run found none it could offer.
          </p>

          <Collapsible v-if="inactiveVariants.length > 0" v-model:open="showInactiveVariants">
            <CollapsibleTrigger as-child>
              <Button type="button" variant="outline" size="sm" data-testid="inactive-variants">
                <ChevronDown class="size-4" />
                {{ showInactiveVariants ? 'Hide' : 'Show' }}
                {{ inactiveVariants.length }} inactive variants
              </Button>
            </CollapsibleTrigger>
            <CollapsibleContent class="pt-3">
              <AdminArticleSpodVariantTable
                :variants="inactiveVariants"
                :appearances="appearances"
              />
            </CollapsibleContent>
          </Collapsible>
        </div>

        <div class="space-y-3 border-t border-border pt-5">
          <h3 class="text-base font-semibold text-foreground">Size chart</h3>
          <img
            v-if="sizeChartUrl"
            :src="sizeChartUrl"
            alt="Size chart"
            class="size-24 rounded-lg border border-border bg-muted/20 object-contain"
            data-testid="size-chart-preview"
          />
          <p v-else class="text-sm text-muted-foreground">
            Spreadconnect published no size chart for this product type.
          </p>
        </div>
      </AdminFormSection>

      <div
        class="sticky bottom-0 z-10 flex items-center gap-2 rounded-lg border border-border bg-background/95 p-3 shadow-sm backdrop-blur sm:gap-3 sm:p-4"
      >
        <Button type="submit" :disabled="isSaving || isDeleting">
          {{ isSaving ? 'Saving...' : 'Save' }}
        </Button>
        <Button as-child type="button" variant="outline" :disabled="isDeleting">
          <RouterLink :to="listLocation">Cancel</RouterLink>
        </Button>

        <Button
          type="button"
          variant="destructive"
          class="ml-auto"
          aria-label="Delete"
          :disabled="isSaving || isDeleting"
          @click="isDeleteDialogOpen = true"
        >
          <Trash2 class="size-4" />
          <span class="hidden sm:inline">Delete</span>
        </Button>
        <ConfirmDeleteDialog
          v-model:open="isDeleteDialogOpen"
          title="Delete article?"
          :description="`This permanently deletes ${synced?.name || 'this article'} including its synced variants and their images. A later sync run creates it again.`"
          confirm-label="Delete"
          :deleting="isDeleting"
          confirm-test-id="confirm-delete-article"
          @confirm="deleteCurrentArticle"
        />
      </div>
    </form>
  </section>
</template>
