<script setup lang="ts">
import { ArrowLeft, ChevronDown, Trash2 } from 'lucide-vue-next'
import { computed, reactive, ref, shallowRef } from 'vue'
import { RouterLink } from 'vue-router'
import AdminArticlePrintFrameCalibrator from '@/components/admin/article/AdminArticlePrintFrameCalibrator.vue'
import AdminArticleTshirtVariantGroups from '@/components/admin/article/AdminArticleTshirtVariantGroups.vue'
import AdminArticlePriceSection from '@/components/admin/pricing/AdminArticlePriceSection.vue'
import AdminEditorSummary, {
  type EditorSummaryFact,
} from '@/components/admin/shared/AdminEditorSummary.vue'
import AdminFormSection from '@/components/admin/shared/AdminFormSection.vue'
import AdminFormSectionNav from '@/components/admin/shared/AdminFormSectionNav.vue'
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
import { hasFormErrors, mapSaveErrors, TSHIRT_SPEC } from '@/lib/adminArticleErrors'
import { formatCents } from '@/lib/adminPrice'
import { formatAdminStamp } from '@/lib/adminStamp'
import { cn } from '@/lib/utils'
import { sizeChartImageUrl, variantExampleImageUrl } from '@/lib/variantExampleImage'
import type { InvalidArticleRequestError } from '@/stores/admin/articles'
import {
  type AdminArticleTshirtVariantDto,
  type AdminTshirtArticleDto,
  type AdminTshirtArticleSyncDto,
  type SaveAdminTshirtArticleRequest,
  TSHIRT_PRINT_ASPECT_RATIOS,
  type TshirtPrintAspectRatio,
  type TshirtPrintFrameDto,
  useAdminTshirtArticlesStore,
} from '@/stores/admin/tshirtArticles'

/**
 * The t-shirt editor. A shirt has two owners since the catalog sync, and so has this screen: the
 * Spreadconnect section *shows* what a sync run wrote — the name, the descriptions, the garment's
 * variants, the size chart — and every other section edits what the shop decides about it.
 *
 * There is no create mode and no variant editing, because a shirt comes into being through a sync
 * run and its variants are the partner's. What the form submits is exactly the shop-owned half.
 *
 * The page reads top-down in the order an operator works: what the shirt *is* (the synced
 * identity), how the shop sorts it, where the print goes, what it costs, and last the partner's
 * variants. The *Active* switch sits in the header, because it is the one decision the whole page
 * leads up to.
 */

/** The sections of the page, in reading order, for the jump list beside the form. */
const SECTIONS = [
  { id: 'section-spreadconnect', label: 'Spreadconnect' },
  { id: 'section-general', label: 'General' },
  { id: 'section-print', label: 'Print' },
  { id: 'section-price', label: 'Price' },
  { id: 'section-variants', label: 'Variants' },
]

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
  'printAspectRatio',
  'printFrame',
  'printFrame.leftPct',
  'printFrame.topPct',
  'printFrame.widthPct',
  'printFrame.heightPct',
  'price',
] as const

type FieldErrorKey = (typeof FIELD_ERROR_KEYS)[number]
type FieldErrors = Partial<Record<FieldErrorKey, string>>

/** The frame of a shirt whose article row somehow carries none: a centred chest print. */
const DEFAULT_PRINT_FRAME: TshirtPrintFrameDto = {
  leftPct: 30,
  topPct: 25,
  widthPct: 40,
  heightPct: 40,
}

const articlesStore = useAdminTshirtArticlesStore()
const articlePrice = useAdminPriceForm({ persistence: 'optional' })

const shop = reactive<ShopFormState>({
  active: false,
  categoryId: null,
  subcategoryId: null,
  defaultVariantId: null,
})

const printAspectRatio = shallowRef<TshirtPrintAspectRatio>('1:1')
const printFrame = ref<TshirtPrintFrameDto>({ ...DEFAULT_PRINT_FRAME })

/** The partner-owned half of the loaded shirt. It is shown, never edited. */
const synced = ref<AdminTshirtArticleDto | null>(null)
const showLongDescription = ref(false)

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
  listRoute: 'admin-tshirt-articles',
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
  return articleName === '' ? 'Edit T-Shirt' : `Edit T-Shirt (${articleName})`
})

const variants = computed<AdminArticleTshirtVariantDto[]>(() => synced.value?.tshirtVariants ?? [])

/** Only an active variant may be the one a customer sees first, so only those are offered. */
const activeVariants = computed(() => variants.value.filter((variant) => variant.active))

const defaultVariantSelectValue = computed({
  get: () => shop.defaultVariantId?.toString() ?? NONE_VALUE,
  set: (value: string) => {
    shop.defaultVariantId = value === NONE_VALUE ? null : Number(value)
  },
})

const sync = computed<AdminTshirtArticleSyncDto | null>(() => synced.value?.sync ?? null)

const isMissingAtSpreadconnect = computed(() => Boolean(sync.value?.missingSince))

/** What a reader wants to know before scrolling: where the shirt is sorted, what it costs, how fresh it is. */
const summaryFacts = computed<EditorSummaryFact[]>(() => {
  const category = categoriesStore.categories.find((entry) => entry.id === shop.categoryId)
  const price = articlePrice.lastCalculatedPrice.value

  return [
    { label: 'Category', value: category?.name ?? null },
    { label: 'Price', value: price ? `${formatCents(price.salesTotal.gross)} EUR` : null },
    { label: 'Last synced', value: formatAdminStamp(sync.value?.syncedAt) },
  ]
})

/**
 * The photo the frame is calibrated on: the picture of the variant an admin picked as the default,
 * or — while none is picked — the first synced variant that has one. It is the same picture the
 * shop editor uses as the backdrop of its preview.
 */
const mockupUrl = computed(() => {
  const withImage =
    variants.value.find(
      (variant) => variant.id === shop.defaultVariantId && variant.exampleImageFilename !== null,
    ) ?? variants.value.find((variant) => variant.exampleImageFilename !== null)

  return withImage?.exampleImageFilename
    ? variantExampleImageUrl('TSHIRT', withImage.exampleImageFilename, 1000)
    : null
})

const sizeChartUrl = computed(() =>
  synced.value?.sizeChartImageFilename
    ? sizeChartImageUrl(synced.value.sizeChartImageFilename, 400)
    : null,
)

const frameErrors = computed(() => ({
  'printFrame.leftPct': fieldErrors['printFrame.leftPct'],
  'printFrame.topPct': fieldErrors['printFrame.topPct'],
  'printFrame.widthPct': fieldErrors['printFrame.widthPct'],
  'printFrame.heightPct': fieldErrors['printFrame.heightPct'],
}))

function resetForm() {
  shop.active = false
  shop.categoryId = null
  shop.subcategoryId = null
  shop.defaultVariantId = null
  printAspectRatio.value = '1:1'
  printFrame.value = { ...DEFAULT_PRINT_FRAME }
  synced.value = null
  showLongDescription.value = false
}

function fillForm(article: AdminTshirtArticleDto) {
  shop.active = article.active
  shop.categoryId = article.categoryId
  shop.subcategoryId = article.subcategoryId
  // Only an active variant is offered as the default one, so only an active one may be preselected:
  // a sync run may have deactivated the stored default, and the select would then hold an id that
  // is not among its options — and submit it unchanged.
  shop.defaultVariantId =
    article.tshirtVariants.find((variant) => variant.isDefault && variant.active)?.id ?? null
  printAspectRatio.value = article.printAspectRatio
  printFrame.value = { ...article.printFrame }
  synced.value = article
}

function clearErrors() {
  for (const key of FIELD_ERROR_KEYS) {
    fieldErrors[key] = undefined
  }
}

/**
 * Shows a rejected write where it belongs. Every problem of a shirt write is a field error on the
 * JSON path of the value that caused it, so the messages go onto the inputs instead of into one
 * anonymous alert.
 */
function applySaveErrors(error: InvalidArticleRequestError) {
  const saveErrors = mapSaveErrors(error.fieldErrors, TSHIRT_SPEC)

  for (const key of FIELD_ERROR_KEYS) {
    fieldErrors[key] = saveErrors.fields[key]
  }

  return saveErrors.other[0] ?? (hasFormErrors(saveErrors) ? null : error.message)
}

function validate(): boolean {
  clearErrors()

  // The database refuses an active article without a category, and the write refuses one without an
  // active default variant or one the partner no longer lists. The form does not let a user get
  // there; an inactive shirt may stay unsorted.
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

  const frame = printFrame.value
  if (frame.leftPct + frame.widthPct > 100) {
    fieldErrors['printFrame.widthPct'] = 'Left plus width must be at most 100.'
  }
  if (frame.topPct + frame.heightPct > 100) {
    fieldErrors['printFrame.heightPct'] = 'Top plus height must be at most 100.'
  }
  if (frame.widthPct <= 0 || frame.heightPct <= 0) {
    fieldErrors.printFrame = 'The print frame needs a width and a height.'
  }

  return FIELD_ERROR_KEYS.every((key) => fieldErrors[key] === undefined)
}

function buildPayload(): SaveAdminTshirtArticleRequest {
  const payload: SaveAdminTshirtArticleRequest = {
    active: shop.active,
    categoryId: shop.categoryId,
    subcategoryId: shop.subcategoryId,
    printAspectRatio: printAspectRatio.value,
    printFrame: { ...printFrame.value },
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
  <section class="space-y-4">
    <AdminPageHeader :title="pageTitle">
      <template v-if="!isLoading" #summary>
        <AdminEditorSummary :facts="summaryFacts">
          <div class="flex items-center gap-2">
            <Checkbox id="article-active" v-model="shop.active" />
            <Label for="article-active" class="font-medium">Active</Label>
            <Badge :variant="shop.active ? 'success' : 'muted'" data-testid="active-badge">
              {{ shop.active ? 'Visible in shop' : 'Not in shop' }}
            </Badge>
          </div>
          <Badge v-if="isMissingAtSpreadconnect" variant="warning" data-testid="spod-missing-badge">
            Missing at Spreadconnect
          </Badge>
        </AdminEditorSummary>
        <p v-if="fieldErrors.active" class="text-sm text-destructive" data-form-error>
          {{ fieldErrors.active }}
        </p>
        <p v-else class="text-xs text-muted-foreground">
          An active shirt needs a category, a price, and an active default variant.
        </p>
      </template>
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

    <div v-else class="lg:grid lg:grid-cols-[minmax(0,1fr)_10rem] lg:items-start lg:gap-8">
      <form class="min-w-0 max-w-5xl space-y-4" @submit.prevent="saveArticle">
        <Alert v-if="generalError" variant="destructive" data-form-error>
          {{ generalError }}
        </Alert>

        <Alert v-if="isMissingAtSpreadconnect" variant="destructive" data-testid="missing-alert">
          Spreadconnect no longer lists this article. It was deactivated and cannot be activated
          again until a sync run finds it.
        </Alert>

        <AdminFormSection
          id="section-spreadconnect"
          title="Spreadconnect"
          description="What this shirt is, as the last sync run found it. Everything here belongs to the Spreadconnect backoffice and is overwritten by the next run. Change it over there, not here."
          read-only
          data-testid="spod-section"
        >
          <div v-if="sync" class="space-y-4" data-testid="spod-identity">
            <dl class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <div>
                <dt class="text-xs text-muted-foreground">Name</dt>
                <dd class="font-medium text-foreground">{{ synced?.name }}</dd>
              </div>
              <div>
                <dt class="text-xs text-muted-foreground">Article ID</dt>
                <dd class="text-foreground">{{ sync.spodArticleId }}</dd>
              </div>
              <div>
                <dt class="text-xs text-muted-foreground">Environment</dt>
                <dd class="text-foreground">{{ sync.environment }}</dd>
              </div>
              <div>
                <dt class="text-xs text-muted-foreground">Last synced</dt>
                <dd class="text-foreground" data-testid="spod-synced-at">
                  {{ formatAdminStamp(sync.syncedAt) }}
                </dd>
              </div>
              <div v-if="sync.missingSince" class="sm:col-span-2 lg:col-span-4">
                <dt class="text-xs text-muted-foreground">Missing since</dt>
                <dd class="text-foreground">{{ formatAdminStamp(sync.missingSince) }}</dd>
              </div>
            </dl>

            <div>
              <p class="text-xs text-muted-foreground">Short description</p>
              <p class="text-foreground">{{ synced?.descriptionShort }}</p>
            </div>

            <Collapsible v-model:open="showLongDescription">
              <CollapsibleTrigger as-child>
                <Button type="button" variant="outline" size="sm" data-testid="long-description">
                  <ChevronDown
                    :class="cn('size-4 transition-transform', showLongDescription && 'rotate-180')"
                  />
                  {{ showLongDescription ? 'Hide' : 'Show' }} long description
                </Button>
              </CollapsibleTrigger>
              <CollapsibleContent class="pt-3">
                <p class="whitespace-pre-line text-foreground">{{ synced?.descriptionLong }}</p>
              </CollapsibleContent>
            </Collapsible>
          </div>
        </AdminFormSection>

        <AdminFormSection
          id="section-general"
          title="General"
          description="How the shop sorts this shirt and which variant a customer sees first."
        >
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
            hint="The colour and size a customer sees first. Only active variants can be picked. Its example image is also the mockup the print frame is drawn on."
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
        </AdminFormSection>

        <AdminFormSection
          id="section-print"
          title="Print"
          description="Where the customer's design sits on the shirt, drawn on the example image of the default variant."
        >
          <Alert v-if="fieldErrors.printFrame" variant="destructive" data-form-error>
            {{ fieldErrors.printFrame }}
          </Alert>

          <FormField
            label="Print aspect ratio"
            for="article-print-aspect-ratio"
            :error="fieldErrors.printAspectRatio"
            hint="The shape the customer's image is generated in."
          >
            <Select v-model="printAspectRatio">
              <SelectTrigger id="article-print-aspect-ratio" class="md:max-w-xs">
                <SelectValue placeholder="Select aspect ratio" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="ratio in TSHIRT_PRINT_ASPECT_RATIOS" :key="ratio" :value="ratio">
                  {{ ratio }}
                </SelectItem>
              </SelectContent>
            </Select>
          </FormField>

          <AdminArticlePrintFrameCalibrator
            :frame="printFrame"
            :print-aspect-ratio="printAspectRatio"
            :mockup-url="mockupUrl"
            :errors="frameErrors"
            @update:frame="printFrame = $event"
          />
        </AdminFormSection>

        <AdminArticlePriceSection
          :article-price="articlePrice"
          :vat-options="priceVatOptions"
          :error="fieldErrors.price"
        />

        <AdminFormSection
          id="section-variants"
          title="Variants"
          description="The colours and sizes of this garment as the last sync run found them. Spreadconnect decides which exist and which are offered."
          read-only
          data-testid="spod-variants-section"
        >
          <AdminArticleTshirtVariantGroups v-if="variants.length > 0" :variants="variants" />
          <p v-else class="text-sm text-muted-foreground">
            This article has no variant. The last sync run found none.
          </p>

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

      <AdminFormSectionNav :sections="SECTIONS" class="hidden lg:sticky lg:top-6 lg:block" />
    </div>
  </section>
</template>
