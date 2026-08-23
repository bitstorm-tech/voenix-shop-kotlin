<script setup lang="ts">
import { ArrowLeft, Trash2 } from 'lucide-vue-next'
import { computed, reactive, ref, shallowRef } from 'vue'
import { RouterLink } from 'vue-router'
import AdminArticlePrintFrameCalibrator from '@/components/admin/article/AdminArticlePrintFrameCalibrator.vue'
import AdminArticleTshirtVariantMatrix from '@/components/admin/article/AdminArticleTshirtVariantMatrix.vue'
import type { TshirtVariantRow } from '@/components/admin/article/tshirtVariantMatrix'
import AdminArticlePriceTab from '@/components/admin/pricing/AdminArticlePriceTab.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { FileInput } from '@/components/ui/file-input'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import { useAdminArticleEditor } from '@/composables/useAdminArticleEditor'
import { NONE_VALUE, useAdminArticleGeneralForm } from '@/composables/useAdminArticleGeneralForm'
import { useAdminPriceForm } from '@/composables/useAdminPriceForm'
import { firstErrorTab, mapSaveErrors, TSHIRT_SPEC } from '@/lib/adminArticleErrors'
import { sizeChartImageUrl, variantExampleImageUrl } from '@/lib/variantExampleImage'
import { InvalidArticleRequestError } from '@/stores/admin/articles'
import {
  type AdminArticleTshirtVariantRequest,
  type AdminTshirtArticleDto,
  type SaveAdminTshirtArticleRequest,
  TSHIRT_PRINT_ASPECT_RATIOS,
  type TshirtPrintAspectRatio,
  type TshirtPrintFrameDto,
  useAdminTshirtArticlesStore,
} from '@/stores/admin/tshirtArticles'

/**
 * The t-shirt editor. It is the mug editor's sibling, not its generalization: the two article types
 * share their general fields and their price tab, and nothing else. A shirt has no measurements and
 * no supplier article number; it has the rectangle its print is placed in, the shape that print is
 * generated in, a size chart, and a variant matrix of colours and sizes.
 */

interface GeneralFormState {
  name: string
  descriptionShort: string
  descriptionLong: string
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  supplierId: number | null
}

/**
 * The messages the form shows on its own inputs. The keys are the ones the backend uses on the JSON
 * paths of a rejected write (`lib/adminArticleErrors.ts` folds the paths onto them), so a
 * client-side rule and a server-side rejection land in the same place.
 */
const FIELD_ERROR_KEYS = [
  'name',
  'descriptionShort',
  'descriptionLong',
  'active',
  'categoryId',
  'subcategoryId',
  'supplierId',
  'printAspectRatio',
  'sizeChartImageFilename',
  'printFrame',
  'printFrame.leftPct',
  'printFrame.topPct',
  'printFrame.widthPct',
  'printFrame.heightPct',
  'tshirtVariants',
  'price',
] as const

type FieldErrorKey = (typeof FIELD_ERROR_KEYS)[number]
type FieldErrors = Partial<Record<FieldErrorKey, string>>

/** A new shirt starts with a centred chest print, which is what almost every shirt wants. */
const DEFAULT_PRINT_FRAME: TshirtPrintFrameDto = {
  leftPct: 30,
  topPct: 25,
  widthPct: 40,
  heightPct: 40,
}

const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024
const ACCEPTED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']

const articlesStore = useAdminTshirtArticlesStore()
const articlePrice = useAdminPriceForm({ persistence: 'optional' })

const TAB_GENERAL = 'general'
const TAB_PRINT = 'print'
const TAB_VARIANTS = 'variants'
const TAB_PRICE = 'price'

const general = reactive<GeneralFormState>({
  name: '',
  descriptionShort: '',
  descriptionLong: '',
  active: false,
  categoryId: null,
  subcategoryId: null,
  supplierId: null,
})

const printAspectRatio = shallowRef<TshirtPrintAspectRatio>('1:1')
const printFrame = ref<TshirtPrintFrameDto>({ ...DEFAULT_PRINT_FRAME })
const sizeChartImageFilename = shallowRef<string | null>(null)
const variants = ref<TshirtVariantRow[]>([])

const fieldErrors = reactive<FieldErrors>({})
/** Backend messages of a single submitted variant, keyed by its index in `tshirtVariants`. */
const variantErrors = ref<Record<number, string>>({})
const sizeChartError = shallowRef<string | null>(null)
const isUploadingSizeChart = shallowRef(false)

const {
  listLocation,
  isEditMode,
  activeTab,
  generalError,
  isLoading,
  isSaving,
  isDeleting,
  isDeleteDialogOpen,
  priceVatOptions,
  categoriesStore,
  suppliersStore,
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
  articleName: () => general.name,
})

const { filteredSubcategories, categorySelectValue, subcategorySelectValue, supplierSelectValue } =
  useAdminArticleGeneralForm(general)

const pageTitle = computed(() => {
  if (!isEditMode.value) {
    return 'New T-Shirt'
  }

  const articleName = general.name.trim()
  return articleName === '' ? 'Edit T-Shirt' : `Edit T-Shirt (${articleName})`
})

/**
 * The photo the frame is calibrated on: the default variant's mockup, or the first one that has a
 * picture at all. It is the same picture the shop editor uses as the backdrop of its preview.
 */
const mockupUrl = computed(() => {
  const withImage =
    variants.value.find((row) => row.isDefault && row.exampleImageFilename !== null) ??
    variants.value.find((row) => row.exampleImageFilename !== null)

  return withImage?.exampleImageFilename
    ? variantExampleImageUrl('TSHIRT', withImage.exampleImageFilename, 1000)
    : null
})

const sizeChartUrl = computed(() =>
  sizeChartImageFilename.value ? sizeChartImageUrl(sizeChartImageFilename.value, 400) : null,
)

const frameErrors = computed(() => ({
  'printFrame.leftPct': fieldErrors['printFrame.leftPct'],
  'printFrame.topPct': fieldErrors['printFrame.topPct'],
  'printFrame.widthPct': fieldErrors['printFrame.widthPct'],
  'printFrame.heightPct': fieldErrors['printFrame.heightPct'],
}))

function resetForm() {
  general.name = ''
  general.descriptionShort = ''
  general.descriptionLong = ''
  general.active = false
  general.categoryId = null
  general.subcategoryId = null
  general.supplierId = null
  printAspectRatio.value = '1:1'
  printFrame.value = { ...DEFAULT_PRINT_FRAME }
  sizeChartImageFilename.value = null
  variants.value = []
}

function fillForm(article: AdminTshirtArticleDto) {
  general.name = article.name
  general.descriptionShort = article.descriptionShort
  general.descriptionLong = article.descriptionLong
  general.active = article.active
  general.categoryId = article.categoryId
  general.subcategoryId = article.subcategoryId
  general.supplierId = article.supplierId
  printAspectRatio.value = article.printAspectRatio
  printFrame.value = { ...article.printFrame }
  sizeChartImageFilename.value = article.sizeChartImageFilename

  variants.value = article.tshirtVariants.map((variant) => ({
    key: variant.id,
    id: variant.id,
    colorName: variant.colorName,
    colorHex: variant.colorHex,
    sizeLabel: variant.sizeLabel,
    spodProductTypeId: variant.spodProductTypeId,
    spodAppearanceId: variant.spodAppearanceId,
    spodSizeId: variant.spodSizeId,
    isDefault: variant.isDefault,
    active: variant.active,
    exampleImageFilename: variant.exampleImageFilename,
  }))
}

function clearErrors() {
  for (const key of FIELD_ERROR_KEYS) {
    fieldErrors[key] = undefined
  }
  variantErrors.value = {}
  sizeChartError.value = null
}

/**
 * Shows a rejected write where it belongs. Every reference problem of a shirt is a field error on
 * the JSON path of the value that caused it, so the messages go onto the inputs and onto the variant
 * rows instead of into one anonymous alert.
 */
function applySaveErrors(error: InvalidArticleRequestError) {
  const saveErrors = mapSaveErrors(error.fieldErrors, TSHIRT_SPEC)

  for (const key of FIELD_ERROR_KEYS) {
    fieldErrors[key] = saveErrors.fields[key]
  }
  variantErrors.value = saveErrors.variants

  const tab = firstErrorTab(saveErrors, TSHIRT_SPEC)
  if (tab !== null) {
    activeTab.value = tab
  }

  return saveErrors.other[0] ?? (tab === null ? error.message : null)
}

/** The client-side half of the rules the backend enforces on `tshirtVariants`. */
function validateVariants(): boolean {
  const rows = variants.value

  if (general.active && !rows.some((row) => row.active)) {
    fieldErrors.tshirtVariants = 'An active article requires at least one active variant.'
    return false
  }

  if (rows.length === 0) {
    return true
  }

  if (rows.filter((row) => row.isDefault).length !== 1) {
    fieldErrors.tshirtVariants = 'Exactly one variant must be marked as default.'
    return false
  }

  if (rows.some((row) => row.spodProductTypeId === null)) {
    fieldErrors.tshirtVariants = 'Every variant needs the SPOD product type id.'
    return false
  }

  if (new Set(rows.map((row) => row.spodProductTypeId)).size > 1) {
    fieldErrors.tshirtVariants = 'All variants must share the same SPOD product type id.'
    return false
  }

  if (rows.some((row) => row.spodAppearanceId === null || row.spodSizeId === null)) {
    fieldErrors.tshirtVariants = 'Every variant needs a SPOD appearance id and a SPOD size id.'
    return false
  }

  const pairs = rows.map((row) => `${row.colorName.trim()} / ${row.sizeLabel.trim()}`)
  if (new Set(pairs).size !== pairs.length) {
    fieldErrors.tshirtVariants = 'Each color and size combination may appear only once.'
    return false
  }

  return true
}

function validate(): boolean {
  clearErrors()

  if (general.name.trim() === '') {
    fieldErrors.name = 'Name is required.'
  }

  if (general.descriptionShort.trim() === '') {
    fieldErrors.descriptionShort = 'Short description is required.'
  }

  if (general.descriptionLong.trim() === '') {
    fieldErrors.descriptionLong = 'Long description is required.'
  }

  // The database refuses an active article without a category, so the form does not let a user get
  // there. An inactive shirt may stay unsorted.
  if (general.active && general.categoryId === null) {
    fieldErrors.categoryId = 'An active article requires a category.'
  }

  if (
    fieldErrors.name ||
    fieldErrors.descriptionShort ||
    fieldErrors.descriptionLong ||
    fieldErrors.categoryId
  ) {
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

  if (!validateVariants()) {
    activeTab.value = TAB_VARIANTS
    return false
  }

  return true
}

function buildPayload(): SaveAdminTshirtArticleRequest {
  const payload: SaveAdminTshirtArticleRequest = {
    name: general.name.trim(),
    descriptionShort: general.descriptionShort.trim(),
    descriptionLong: general.descriptionLong.trim(),
    active: general.active,
    categoryId: general.categoryId,
    subcategoryId: general.subcategoryId,
    supplierId: general.supplierId,
    printAspectRatio: printAspectRatio.value,
    sizeChartImageFilename: sizeChartImageFilename.value,
    printFrame: { ...printFrame.value },
    tshirtVariants: variants.value.map(
      (row): AdminArticleTshirtVariantRequest => ({
        id: row.id,
        colorName: row.colorName.trim(),
        colorHex: row.colorHex,
        sizeLabel: row.sizeLabel.trim(),
        // `validate()` ran first, so these three are filled. The zero is unreachable and only keeps
        // the payload type honest.
        spodProductTypeId: row.spodProductTypeId ?? 0,
        spodAppearanceId: row.spodAppearanceId ?? 0,
        spodSizeId: row.spodSizeId ?? 0,
        isDefault: row.isDefault,
        active: row.active,
        exampleImageFilename: row.exampleImageFilename,
      }),
    ),
  }

  const pricePayload = articlePrice.getSavePayload()
  if (pricePayload !== undefined) {
    payload.price = pricePayload
  }

  return payload
}

async function uploadSizeChart(files: File[]) {
  const file = files[0]
  sizeChartError.value = null

  if (!file) {
    return
  }

  if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) {
    sizeChartError.value = 'Size chart must be a PNG, JPEG, or WebP file.'
    return
  }

  if (file.size > MAX_IMAGE_SIZE_BYTES) {
    sizeChartError.value = 'Size chart must not exceed 10 MB.'
    return
  }

  isUploadingSizeChart.value = true

  try {
    sizeChartImageFilename.value = await articlesStore.uploadSizeChartImage(file)
  } catch (error) {
    // A rejected pre-upload is a `400` whose message sits on the `file` field of the request.
    sizeChartError.value =
      error instanceof InvalidArticleRequestError
        ? (error.fieldError('file') ?? error.message)
        : error instanceof Error
          ? error.message
          : 'Failed to upload the size chart.'
  } finally {
    isUploadingSizeChart.value = false
  }
}

function removeSizeChart() {
  sizeChartImageFilename.value = null
  sizeChartError.value = null
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

      <Tabs v-model="activeTab" class="space-y-5">
        <TabsList
          class="flex w-full flex-wrap justify-start gap-1 border border-border bg-muted/30"
        >
          <TabsTrigger
            v-for="tab in [
              { value: TAB_GENERAL, label: 'General' },
              { value: TAB_PRINT, label: 'Print' },
              { value: TAB_VARIANTS, label: 'Variants' },
              { value: TAB_PRICE, label: 'Price Calculation' },
            ]"
            :key="tab.value"
            :value="tab.value"
          >
            {{ tab.label }}
          </TabsTrigger>
        </TabsList>

        <TabsContent :value="TAB_GENERAL" class="space-y-5 focus-visible:outline-none">
          <FormField label="Name" for="article-name" :error="fieldErrors.name">
            <Input
              id="article-name"
              v-model="general.name"
              type="text"
              placeholder="Article name"
              :aria-invalid="fieldErrors.name ? true : undefined"
            />
          </FormField>

          <FormField
            label="Short description"
            for="article-description-short"
            :error="fieldErrors.descriptionShort"
          >
            <Textarea
              id="article-description-short"
              v-model="general.descriptionShort"
              rows="2"
              placeholder="Short description shown in listings"
              :aria-invalid="fieldErrors.descriptionShort ? true : undefined"
            />
          </FormField>

          <FormField
            label="Long description"
            for="article-description-long"
            :error="fieldErrors.descriptionLong"
          >
            <Textarea
              id="article-description-long"
              v-model="general.descriptionLong"
              rows="5"
              placeholder="Detailed product description"
              :aria-invalid="fieldErrors.descriptionLong ? true : undefined"
            />
          </FormField>

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
              :hint="general.categoryId === null ? 'Select a category first.' : undefined"
            >
              <Select v-model="subcategorySelectValue" :disabled="general.categoryId === null">
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
            label="Supplier"
            for="article-supplier"
            :error="fieldErrors.supplierId"
            hint="A shirt is ordered from the print-on-demand partner by its SPOD ids, so it has no supplier article number."
          >
            <Select v-model="supplierSelectValue">
              <SelectTrigger id="article-supplier">
                <SelectValue placeholder="Select supplier" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem :value="NONE_VALUE">No supplier</SelectItem>
                <SelectItem
                  v-for="supplier in suppliersStore.suppliers"
                  :key="supplier.id"
                  :value="supplier.id.toString()"
                >
                  {{ supplier.name }}
                </SelectItem>
              </SelectContent>
            </Select>
          </FormField>

          <div class="flex items-center gap-3 border-t border-border pt-5">
            <Checkbox id="article-active" v-model="general.active" />
            <div>
              <Label for="article-active">Active</Label>
              <p class="text-sm text-muted-foreground">
                Active articles are visible in the shop. Requires a category, a price, and at least
                one active variant.
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

          <fieldset class="space-y-3 border-t border-border pt-5">
            <legend class="text-base font-semibold text-foreground">Size chart</legend>
            <p class="text-sm text-muted-foreground">
              The measurements a customer picks a size from. PNG, JPEG, or WebP, max 10 MB.
            </p>
            <div class="flex items-center gap-4">
              <img
                v-if="sizeChartUrl"
                :src="sizeChartUrl"
                alt="Size chart preview"
                class="size-24 shrink-0 rounded-lg border border-border bg-muted/20 object-contain"
                data-testid="size-chart-preview"
              />
              <div
                v-else
                class="flex size-24 shrink-0 items-center justify-center rounded-lg border border-dashed border-border text-xs text-muted-foreground"
              >
                No size chart
              </div>
              <div class="flex flex-wrap items-center gap-2">
                <FileInput
                  id="article-size-chart"
                  accept="image/png,image/jpeg,image/webp"
                  button-test-id="size-chart-upload"
                  input-test-id="size-chart-input"
                  reset-on-select
                  size="sm"
                  variant="outline"
                  :disabled="isUploadingSizeChart"
                  @change="uploadSizeChart"
                >
                  {{
                    isUploadingSizeChart
                      ? 'Uploading...'
                      : sizeChartImageFilename
                        ? 'Replace size chart'
                        : 'Upload size chart'
                  }}
                </FileInput>
                <Button
                  v-if="sizeChartImageFilename"
                  type="button"
                  variant="outline"
                  size="sm"
                  data-testid="size-chart-remove"
                  @click="removeSizeChart"
                >
                  Remove
                </Button>
              </div>
            </div>
            <p v-if="sizeChartError" class="text-sm text-destructive">{{ sizeChartError }}</p>
            <p v-if="fieldErrors.sizeChartImageFilename" class="text-sm text-destructive">
              {{ fieldErrors.sizeChartImageFilename }}
            </p>
          </fieldset>
        </TabsContent>

        <TabsContent :value="TAB_VARIANTS" class="space-y-4 focus-visible:outline-none">
          <AdminArticleTshirtVariantMatrix
            :rows="variants"
            :variant-errors="variantErrors"
            :list-error="fieldErrors.tshirtVariants ?? null"
            @update:rows="variants = $event"
          />
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

        <template v-if="isEditMode">
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
            :description="`This permanently deletes ${general.name || 'this article'} including its variants and their example images. This action cannot be undone.`"
            confirm-label="Delete Article"
            :deleting="isDeleting"
            confirm-test-id="confirm-delete-article"
            @confirm="deleteCurrentArticle"
          />
        </template>
      </div>
    </Card>
  </section>
</template>
