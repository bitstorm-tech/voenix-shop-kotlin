<script setup lang="ts">
import { ArrowLeft, ChevronDown, Trash2 } from 'lucide-vue-next'
import { computed, reactive, ref, shallowRef } from 'vue'
import { RouterLink } from 'vue-router'
import AdminArticlePrintFrameCalibrator from '@/components/admin/article/AdminArticlePrintFrameCalibrator.vue'
import AdminArticleTshirtVariantTable from '@/components/admin/article/AdminArticleTshirtVariantTable.vue'
import AdminArticlePriceTab from '@/components/admin/pricing/AdminArticlePriceTab.vue'
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useAdminArticleEditor } from '@/composables/useAdminArticleEditor'
import { NONE_VALUE, useAdminArticleGeneralForm } from '@/composables/useAdminArticleGeneralForm'
import { useAdminPriceForm } from '@/composables/useAdminPriceForm'
import { firstErrorTab, mapSaveErrors, TSHIRT_SPEC } from '@/lib/adminArticleErrors'
import { formatAdminStamp } from '@/lib/adminStamp'
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
 * The t-shirt editor. A shirt has two owners since ADR 0003, and so has this screen: the
 * Spreadconnect tab *shows* what a sync run wrote — the name, the descriptions, the garment's
 * variants, the size chart — and every other tab edits what the shop decides about it.
 *
 * There is no create mode and no variant editing, because a shirt comes into being through a sync
 * run and its variants are the partner's. What the form submits is exactly the shop-owned half.
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

const TAB_GENERAL = 'general'
const TAB_PRINT = 'print'
const TAB_SPOD = 'spreadconnect'
const TAB_PRICE = 'price'

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
const showInactiveVariants = ref(false)

const fieldErrors = reactive<FieldErrors>({})

const {
  listLocation,
  activeTab,
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
  priceTab: TAB_PRICE,
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
const inactiveVariants = computed(() => variants.value.filter((variant) => !variant.active))

const defaultVariantSelectValue = computed({
  get: () => shop.defaultVariantId?.toString() ?? NONE_VALUE,
  set: (value: string) => {
    shop.defaultVariantId = value === NONE_VALUE ? null : Number(value)
  },
})

const sync = computed<AdminTshirtArticleSyncDto | null>(() => synced.value?.sync ?? null)

const isMissingAtSpreadconnect = computed(() => Boolean(sync.value?.missingSince))

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
  showInactiveVariants.value = false
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

  const tab = firstErrorTab(saveErrors, TSHIRT_SPEC)
  if (tab !== null) {
    activeTab.value = tab
  }

  return saveErrors.other[0] ?? (tab === null ? error.message : null)
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

  if (fieldErrors.categoryId || fieldErrors.defaultVariantId || fieldErrors.active) {
    activeTab.value = TAB_GENERAL
    return false
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

  if (
    fieldErrors['printFrame.widthPct'] ||
    fieldErrors['printFrame.heightPct'] ||
    fieldErrors.printFrame
  ) {
    activeTab.value = TAB_PRINT
    return false
  }

  return true
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

    <Card v-else as="form" class="space-y-6 p-5" @submit.prevent="saveArticle">
      <Alert v-if="generalError" variant="destructive">
        {{ generalError }}
      </Alert>

      <Alert v-if="isMissingAtSpreadconnect" variant="destructive" data-testid="missing-alert">
        Spreadconnect no longer lists this article. It was deactivated and cannot be activated again
        until a sync run finds it.
      </Alert>

      <Tabs v-model="activeTab" class="space-y-5">
        <TabsList
          class="flex w-full flex-wrap justify-start gap-1 border border-border bg-muted/30"
        >
          <TabsTrigger
            v-for="tab in [
              { value: TAB_GENERAL, label: 'General' },
              { value: TAB_PRINT, label: 'Print' },
              { value: TAB_SPOD, label: 'Spreadconnect' },
              { value: TAB_PRICE, label: 'Price Calculation' },
            ]"
            :key="tab.value"
            :value="tab.value"
          >
            {{ tab.label }}
          </TabsTrigger>
        </TabsList>

        <TabsContent :value="TAB_GENERAL" class="space-y-5 focus-visible:outline-none">
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
                Active articles are visible in the shop. Requires a category, a price, and an active
                default variant.
              </p>
              <p v-if="fieldErrors.active" class="text-sm text-destructive">
                {{ fieldErrors.active }}
              </p>
            </div>
          </div>
        </TabsContent>

        <TabsContent :value="TAB_PRINT" class="space-y-5 focus-visible:outline-none">
          <Alert v-if="fieldErrors.printFrame" variant="destructive">
            {{ fieldErrors.printFrame }}
          </Alert>

          <FormField
            label="Print aspect ratio"
            for="article-print-aspect-ratio"
            :error="fieldErrors.printAspectRatio"
            hint="The shape the customer's image is generated in."
          >
            <Select v-model="printAspectRatio">
              <SelectTrigger id="article-print-aspect-ratio">
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
        </TabsContent>

        <TabsContent :value="TAB_SPOD" class="space-y-5 focus-visible:outline-none">
          <p class="text-sm text-muted-foreground">
            Everything on this tab belongs to the Spreadconnect backoffice and is overwritten by the
            next sync run. Change it over there, not here.
          </p>

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
            <h2 class="text-base font-semibold text-foreground">Variants</h2>
            <AdminArticleTshirtVariantTable
              v-if="activeVariants.length > 0"
              :variants="activeVariants"
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
                <AdminArticleTshirtVariantTable :variants="inactiveVariants" />
              </CollapsibleContent>
            </Collapsible>
          </div>

          <div class="space-y-3 border-t border-border pt-5">
            <h2 class="text-base font-semibold text-foreground">Size chart</h2>
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
        </TabsContent>

        <TabsContent :value="TAB_PRICE" class="focus-visible:outline-none">
          <AdminArticlePriceTab
            :article-price="articlePrice"
            :vat-options="priceVatOptions"
            :error="fieldErrors.price"
          />
        </TabsContent>
      </Tabs>

      <div class="flex flex-col gap-3 border-t border-border pt-5 sm:flex-row sm:items-center">
        <Button type="submit" :disabled="isSaving || isDeleting">
          {{ isSaving ? 'Saving...' : 'Save Article' }}
        </Button>
        <Button as-child type="button" variant="outline" :disabled="isDeleting">
          <RouterLink :to="listLocation">Cancel</RouterLink>
        </Button>

        <Button
          type="button"
          variant="destructive"
          class="sm:ml-auto"
          :disabled="isSaving || isDeleting"
          @click="isDeleteDialogOpen = true"
        >
          <Trash2 class="size-4" />
          Delete Article
        </Button>
        <ConfirmDeleteDialog
          v-model:open="isDeleteDialogOpen"
          title="Delete article?"
          :description="`This permanently deletes ${synced?.name || 'this article'} including its synced variants and their images. A later sync run creates it again.`"
          confirm-label="Delete Article"
          :deleting="isDeleting"
          confirm-test-id="confirm-delete-article"
          @confirm="deleteCurrentArticle"
        />
      </div>
    </Card>
  </section>
</template>
