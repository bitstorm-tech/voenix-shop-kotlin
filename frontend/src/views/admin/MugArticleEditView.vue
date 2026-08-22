<script setup lang="ts">
import { ArrowLeft, Pencil, Plus, Trash2 } from 'lucide-vue-next'
import { computed, reactive, ref, shallowRef } from 'vue'
import { RouterLink } from 'vue-router'
import AdminArticleMugVariantDialog from '@/components/admin/article/AdminArticleMugVariantDialog.vue'
import type { MugVariantFormValue } from '@/components/admin/article/mugVariantForm'
import AdminArticlePriceTab from '@/components/admin/pricing/AdminArticlePriceTab.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import { useAdminArticleEditor } from '@/composables/useAdminArticleEditor'
import { NONE_VALUE, useAdminArticleGeneralForm } from '@/composables/useAdminArticleGeneralForm'
import { useAdminPriceForm } from '@/composables/useAdminPriceForm'
import { firstErrorTab, mapSaveErrors, MUG_SPEC } from '@/lib/adminArticleErrors'
import { optionalText } from '@/lib/forms'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import {
  type AdminArticleMugVariantRequest,
  type AdminMugArticleDto,
  InvalidArticleRequestError,
  type SaveAdminMugArticleRequest,
} from '@/stores/admin/articles'

interface EditorVariant {
  key: number
  id: number | null
  name: string
  insideColorCode: string
  outsideColorCode: string
  isDefault: boolean
  active: boolean
  exampleImageFilename: string | null
}

interface GeneralFormState {
  name: string
  descriptionShort: string
  descriptionLong: string
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  supplierId: number | null
  supplierArticleName: string
  supplierArticleNumber: string
}

interface DetailsFormState {
  heightMm: string | number
  diameterMm: string | number
  printTemplateWidthMm: string | number
  printTemplateHeightMm: string | number
  fillingQuantity: string
  dishwasherSafe: boolean
  documentFormatWidthMm: string | number
  documentFormatHeightMm: string | number
  documentFormatMarginBottomMm: string | number
}

/**
 * The messages the form shows on its own inputs. The keys are the ones the backend uses on the
 * JSON paths of a rejected write, so a client-side rule and a server-side rejection land in the
 * same place (`lib/adminArticleErrors.ts` folds the paths onto them).
 */
interface FieldErrors {
  name?: string
  descriptionShort?: string
  descriptionLong?: string
  active?: string
  categoryId?: string
  subcategoryId?: string
  supplierId?: string
  supplierArticleName?: string
  supplierArticleNumber?: string
  heightMm?: string
  fillingQuantity?: string
  diameterMm?: string
  printTemplateWidthMm?: string
  printTemplateHeightMm?: string
  documentFormatWidthMm?: string
  documentFormatHeightMm?: string
  documentFormatMarginBottomMm?: string
  mugVariants?: string
  price?: string
}

const FIELD_ERROR_KEYS = [
  'name',
  'descriptionShort',
  'descriptionLong',
  'active',
  'categoryId',
  'subcategoryId',
  'supplierId',
  'supplierArticleName',
  'supplierArticleNumber',
  'heightMm',
  'fillingQuantity',
  'diameterMm',
  'printTemplateWidthMm',
  'printTemplateHeightMm',
  'documentFormatWidthMm',
  'documentFormatHeightMm',
  'documentFormatMarginBottomMm',
  'mugVariants',
  'price',
] as const satisfies readonly (keyof FieldErrors)[]

const articlePrice = useAdminPriceForm({ persistence: 'optional' })

const TAB_GENERAL = 'general'
const TAB_DETAILS = 'details'
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
  supplierArticleName: '',
  supplierArticleNumber: '',
})

const details = reactive<DetailsFormState>({
  heightMm: '',
  diameterMm: '',
  printTemplateWidthMm: '',
  printTemplateHeightMm: '',
  fillingQuantity: '',
  dishwasherSafe: true,
  documentFormatWidthMm: '',
  documentFormatHeightMm: '',
  documentFormatMarginBottomMm: '',
})

const hasMugDetailsInput = computed(() =>
  [
    details.heightMm,
    details.diameterMm,
    details.printTemplateWidthMm,
    details.printTemplateHeightMm,
    details.fillingQuantity,
    details.documentFormatWidthMm,
    details.documentFormatHeightMm,
    details.documentFormatMarginBottomMm,
  ].some((value) => String(value).trim() !== ''),
)

const variants = ref<EditorVariant[]>([])
const fieldErrors = reactive<FieldErrors>({})
/** Backend messages of a single submitted variant, keyed by its index in `mugVariants`. */
const variantErrors = ref<Record<number, string>>({})
const isVariantDialogOpen = shallowRef(false)
const editingVariantKey = shallowRef<number | null>(null)
let variantKeySequence = 0

const {
  route,
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
  articleType: 'MUG',
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
    return 'New Article'
  }

  const articleName = general.name.trim()
  return articleName === '' ? 'Edit Article' : `Edit Article (${articleName})`
})

const editingVariant = computed(() => {
  if (editingVariantKey.value === null) {
    return null
  }

  return variants.value.find((variant) => variant.key === editingVariantKey.value) ?? null
})

const variantDialogLocksDefault = computed(() => {
  if (editingVariantKey.value === null) {
    return variants.value.length === 0
  }

  return variants.value.length === 1
})

function isValidColor(value: string) {
  return /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value)
}

function nextVariantKey() {
  variantKeySequence += 1
  return variantKeySequence
}

function resetForm() {
  general.name = ''
  general.descriptionShort = ''
  general.descriptionLong = ''
  general.active = false
  general.categoryId = null
  general.subcategoryId = null
  general.supplierId = null
  general.supplierArticleName = ''
  general.supplierArticleNumber = ''
  details.heightMm = ''
  details.diameterMm = ''
  details.printTemplateWidthMm = ''
  details.printTemplateHeightMm = ''
  details.fillingQuantity = ''
  details.dishwasherSafe = true
  details.documentFormatWidthMm = ''
  details.documentFormatHeightMm = ''
  details.documentFormatMarginBottomMm = ''
  variants.value = []
  isVariantDialogOpen.value = false
}

function fillForm(article: AdminMugArticleDto) {
  general.name = article.name
  general.descriptionShort = article.descriptionShort
  general.descriptionLong = article.descriptionLong
  general.active = article.active
  general.categoryId = article.categoryId
  general.subcategoryId = article.subcategoryId
  general.supplierId = article.supplierId
  general.supplierArticleName = article.supplierArticleName ?? ''
  general.supplierArticleNumber = article.supplierArticleNumber ?? ''

  details.heightMm = article.mugDetails?.heightMm.toString() ?? ''
  details.diameterMm = article.mugDetails?.diameterMm.toString() ?? ''
  details.printTemplateWidthMm = article.mugDetails?.printTemplateWidthMm.toString() ?? ''
  details.printTemplateHeightMm = article.mugDetails?.printTemplateHeightMm.toString() ?? ''
  details.fillingQuantity = article.mugDetails?.fillingQuantity ?? ''
  details.dishwasherSafe = article.mugDetails?.dishwasherSafe ?? true
  details.documentFormatWidthMm = article.mugDetails?.documentFormatWidthMm?.toString() ?? ''
  details.documentFormatHeightMm = article.mugDetails?.documentFormatHeightMm?.toString() ?? ''
  details.documentFormatMarginBottomMm =
    article.mugDetails?.documentFormatMarginBottomMm?.toString() ?? ''

  variants.value = article.mugVariants.map((variant) => ({
    key: nextVariantKey(),
    id: variant.id,
    name: variant.name,
    insideColorCode: variant.insideColorCode,
    outsideColorCode: variant.outsideColorCode,
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
}

/**
 * Shows a rejected write where it belongs. Every reference problem of a mug is a field error on the
 * JSON path of the value that caused it, so the messages go onto the inputs and onto the variant
 * rows instead of into one anonymous alert.
 */
function applySaveErrors(error: InvalidArticleRequestError) {
  const saveErrors = mapSaveErrors(error.fieldErrors, MUG_SPEC)

  for (const key of FIELD_ERROR_KEYS) {
    fieldErrors[key] = saveErrors.fields[key]
  }
  variantErrors.value = saveErrors.variants

  const tab = firstErrorTab(saveErrors, MUG_SPEC)
  if (tab !== null) {
    activeTab.value = tab
  }

  return saveErrors.other[0] ?? (tab === null ? error.message : null)
}

function parseRequiredPositiveInt(value: string | number): number | null {
  const trimmedValue = String(value).trim()
  if (trimmedValue === '') {
    return null
  }

  const parsedValue = Number(trimmedValue)
  return Number.isInteger(parsedValue) && parsedValue > 0 ? parsedValue : null
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

  // The database refuses an active mug without a category, so the form does not let a user get
  // there. An inactive mug may stay unsorted.
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

  if (hasMugDetailsInput.value || general.active) {
    const requiredFields = [
      ['heightMm', details.heightMm, 'Height'],
      ['diameterMm', details.diameterMm, 'Diameter'],
      ['printTemplateWidthMm', details.printTemplateWidthMm, 'Print template width'],
      ['printTemplateHeightMm', details.printTemplateHeightMm, 'Print template height'],
    ] as const

    for (const [field, value, label] of requiredFields) {
      if (parseRequiredPositiveInt(value) === null) {
        fieldErrors[field] = `${label} must be a positive whole number.`
      }
    }

    const optionalFields = [
      ['documentFormatWidthMm', details.documentFormatWidthMm, 'Document format width'],
      ['documentFormatHeightMm', details.documentFormatHeightMm, 'Document format height'],
      [
        'documentFormatMarginBottomMm',
        details.documentFormatMarginBottomMm,
        'Document format margin bottom',
      ],
    ] as const

    for (const [field, value, label] of optionalFields) {
      if (String(value).trim() !== '' && parseRequiredPositiveInt(value) === null) {
        fieldErrors[field] = `${label} must be a positive whole number.`
      }
    }

    if (
      fieldErrors.heightMm ||
      fieldErrors.diameterMm ||
      fieldErrors.printTemplateWidthMm ||
      fieldErrors.printTemplateHeightMm ||
      fieldErrors.documentFormatWidthMm ||
      fieldErrors.documentFormatHeightMm ||
      fieldErrors.documentFormatMarginBottomMm
    ) {
      activeTab.value = TAB_DETAILS
      return false
    }
  }

  if (general.active && !variants.value.some((variant) => variant.active)) {
    fieldErrors.mugVariants = 'An active article requires at least one active variant.'
    activeTab.value = TAB_VARIANTS
    return false
  }

  // A partial unique index in the database allows one default per mug. The dialog keeps the flag
  // exclusive while editing; this is the guard for the state that reaches the save.
  if (variants.value.filter((variant) => variant.isDefault).length > 1) {
    fieldErrors.mugVariants = 'At most one variant may be the default.'
    activeTab.value = TAB_VARIANTS
    return false
  }

  return true
}

function optionalInt(value: string | number) {
  const trimmedValue = String(value).trim()
  return trimmedValue === '' ? null : Number(trimmedValue)
}

function buildPayload(): SaveAdminMugArticleRequest {
  const payload: SaveAdminMugArticleRequest = {
    name: general.name.trim(),
    descriptionShort: general.descriptionShort.trim(),
    descriptionLong: general.descriptionLong.trim(),
    active: general.active,
    categoryId: general.categoryId,
    subcategoryId: general.subcategoryId,
    supplierId: general.supplierId,
    supplierArticleName: optionalText(general.supplierArticleName),
    supplierArticleNumber: optionalText(general.supplierArticleNumber),
    mugDetails: hasMugDetailsInput.value
      ? {
          heightMm: Number(details.heightMm),
          diameterMm: Number(details.diameterMm),
          printTemplateWidthMm: Number(details.printTemplateWidthMm),
          printTemplateHeightMm: Number(details.printTemplateHeightMm),
          fillingQuantity: optionalText(details.fillingQuantity),
          dishwasherSafe: details.dishwasherSafe,
          documentFormatWidthMm: optionalInt(details.documentFormatWidthMm),
          documentFormatHeightMm: optionalInt(details.documentFormatHeightMm),
          documentFormatMarginBottomMm: optionalInt(details.documentFormatMarginBottomMm),
        }
      : null,
    mugVariants: variants.value.map(
      (variant): AdminArticleMugVariantRequest => ({
        id: variant.id,
        name: variant.name,
        insideColorCode: variant.insideColorCode,
        outsideColorCode: variant.outsideColorCode,
        isDefault: variant.isDefault,
        active: variant.active,
        exampleImageFilename: variant.exampleImageFilename,
      }),
    ),
  }

  const pricePayload = articlePrice.getSavePayload()
  if (pricePayload !== undefined) {
    payload.price = pricePayload
  }

  return payload
}

function openCreateVariantDialog() {
  editingVariantKey.value = null
  isVariantDialogOpen.value = true
}

function openEditVariantDialog(variant: EditorVariant) {
  editingVariantKey.value = variant.key
  isVariantDialogOpen.value = true
}

function ensureSingleDefault(preferredKey: number | null) {
  const defaults = variants.value.filter((variant) => variant.isDefault)
  if (defaults.length === 1) {
    return
  }

  if (defaults.length > 1) {
    for (const variant of variants.value) {
      variant.isDefault = preferredKey !== null && variant.key === preferredKey
    }
  }

  const firstVariant = variants.value[0]
  if (firstVariant && !variants.value.some((variant) => variant.isDefault)) {
    firstVariant.isDefault = true
  }
}

function saveVariant(payload: MugVariantFormValue) {
  if (editingVariantKey.value === null) {
    const newVariant: EditorVariant = {
      key: nextVariantKey(),
      id: null,
      ...payload,
    }
    variants.value = [...variants.value, newVariant]
    ensureSingleDefault(payload.isDefault ? newVariant.key : null)
    return
  }

  const variant = variants.value.find((entry) => entry.key === editingVariantKey.value)
  if (!variant) {
    return
  }

  variant.name = payload.name
  variant.insideColorCode = payload.insideColorCode
  variant.outsideColorCode = payload.outsideColorCode
  variant.isDefault = payload.isDefault
  variant.active = payload.active
  variant.exampleImageFilename = payload.exampleImageFilename
  ensureSingleDefault(payload.isDefault ? variant.key : null)
}

function removeVariant(variant: EditorVariant) {
  variants.value = variants.value.filter((entry) => entry.key !== variant.key)
  ensureSingleDefault(null)
}
</script>

<template>
  <section class="max-w-5xl space-y-4">
    <AdminPageHeader :title="pageTitle">
      <template #actions>
        <Button as-child variant="outline" size="sm" class="self-start">
          <RouterLink :to="{ name: 'admin-articles', query: route.query }">
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
              { value: TAB_DETAILS, label: 'Details' },
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

          <fieldset class="space-y-4 border-t border-border pt-5">
            <legend class="text-base font-semibold text-foreground">Supplier</legend>
            <div class="grid gap-4 md:grid-cols-3">
              <FormField label="Supplier" for="article-supplier" :error="fieldErrors.supplierId">
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
              <FormField
                label="Supplier article name"
                for="article-supplier-article-name"
                :error="fieldErrors.supplierArticleName"
              >
                <Input
                  id="article-supplier-article-name"
                  v-model="general.supplierArticleName"
                  type="text"
                  :aria-invalid="fieldErrors.supplierArticleName ? true : undefined"
                />
              </FormField>
              <FormField
                label="Supplier article number"
                for="article-supplier-article-number"
                :error="fieldErrors.supplierArticleNumber"
              >
                <Input
                  id="article-supplier-article-number"
                  v-model="general.supplierArticleNumber"
                  type="text"
                  :aria-invalid="fieldErrors.supplierArticleNumber ? true : undefined"
                />
              </FormField>
            </div>
          </fieldset>

          <div class="flex items-center gap-3 border-t border-border pt-5">
            <Checkbox id="article-active" v-model="general.active" />
            <div>
              <Label for="article-active">Active</Label>
              <p class="text-sm text-muted-foreground">
                Active articles are visible in the shop. Requires a category, a price, complete mug
                details, and at least one active variant.
              </p>
              <p v-if="fieldErrors.active" class="text-sm text-destructive">
                {{ fieldErrors.active }}
              </p>
            </div>
          </div>
        </TabsContent>

        <TabsContent :value="TAB_DETAILS" class="space-y-5 focus-visible:outline-none">
          <p class="text-sm text-muted-foreground">
            Physical mug characteristics. Required before the article can be set active.
          </p>

          <div class="space-y-5">
            <div class="grid gap-4 md:grid-cols-2">
              <FormField label="Height (mm)" for="article-height" :error="fieldErrors.heightMm">
                <Input
                  id="article-height"
                  v-model="details.heightMm"
                  type="number"
                  inputmode="numeric"
                  min="1"
                  step="1"
                  placeholder="e.g. 95"
                  :aria-invalid="fieldErrors.heightMm ? true : undefined"
                />
              </FormField>
              <FormField
                label="Diameter (mm)"
                for="article-diameter"
                :error="fieldErrors.diameterMm"
              >
                <Input
                  id="article-diameter"
                  v-model="details.diameterMm"
                  type="number"
                  inputmode="numeric"
                  min="1"
                  step="1"
                  placeholder="e.g. 82"
                  :aria-invalid="fieldErrors.diameterMm ? true : undefined"
                />
              </FormField>
              <FormField
                label="Print template width (mm)"
                for="article-print-template-width"
                :error="fieldErrors.printTemplateWidthMm"
              >
                <Input
                  id="article-print-template-width"
                  v-model="details.printTemplateWidthMm"
                  type="number"
                  inputmode="numeric"
                  min="1"
                  step="1"
                  placeholder="e.g. 200"
                  :aria-invalid="fieldErrors.printTemplateWidthMm ? true : undefined"
                />
              </FormField>
              <FormField
                label="Print template height (mm)"
                for="article-print-template-height"
                :error="fieldErrors.printTemplateHeightMm"
              >
                <Input
                  id="article-print-template-height"
                  v-model="details.printTemplateHeightMm"
                  type="number"
                  inputmode="numeric"
                  min="1"
                  step="1"
                  placeholder="e.g. 90"
                  :aria-invalid="fieldErrors.printTemplateHeightMm ? true : undefined"
                />
              </FormField>
              <FormField
                label="Filling quantity"
                for="article-filling-quantity"
                :error="fieldErrors.fillingQuantity"
              >
                <Input
                  id="article-filling-quantity"
                  v-model="details.fillingQuantity"
                  type="text"
                  placeholder="e.g. 325ml"
                  :aria-invalid="fieldErrors.fillingQuantity ? true : undefined"
                />
              </FormField>
              <div class="flex items-center gap-3 md:pt-7">
                <Checkbox id="article-dishwasher-safe" v-model="details.dishwasherSafe" />
                <Label for="article-dishwasher-safe">Dishwasher safe</Label>
              </div>
            </div>

            <fieldset class="space-y-4 border-t border-border pt-5">
              <legend class="text-base font-semibold text-foreground">
                Document format (optional)
              </legend>
              <div class="grid gap-4 md:grid-cols-3">
                <FormField
                  label="Width (mm)"
                  for="article-document-width"
                  :error="fieldErrors.documentFormatWidthMm"
                >
                  <Input
                    id="article-document-width"
                    v-model="details.documentFormatWidthMm"
                    type="number"
                    inputmode="numeric"
                    min="1"
                    step="1"
                    :aria-invalid="fieldErrors.documentFormatWidthMm ? true : undefined"
                  />
                </FormField>
                <FormField
                  label="Height (mm)"
                  for="article-document-height"
                  :error="fieldErrors.documentFormatHeightMm"
                >
                  <Input
                    id="article-document-height"
                    v-model="details.documentFormatHeightMm"
                    type="number"
                    inputmode="numeric"
                    min="1"
                    step="1"
                    :aria-invalid="fieldErrors.documentFormatHeightMm ? true : undefined"
                  />
                </FormField>
                <FormField
                  label="Margin bottom (mm)"
                  for="article-document-margin-bottom"
                  :error="fieldErrors.documentFormatMarginBottomMm"
                >
                  <Input
                    id="article-document-margin-bottom"
                    v-model="details.documentFormatMarginBottomMm"
                    type="number"
                    inputmode="numeric"
                    min="1"
                    step="1"
                    :aria-invalid="fieldErrors.documentFormatMarginBottomMm ? true : undefined"
                  />
                </FormField>
              </div>
            </fieldset>
          </div>
        </TabsContent>

        <TabsContent :value="TAB_VARIANTS" class="space-y-4 focus-visible:outline-none">
          <Alert v-if="fieldErrors.mugVariants" variant="destructive">
            {{ fieldErrors.mugVariants }}
          </Alert>

          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <p class="text-sm text-muted-foreground">
              Color variants of this article. Exactly one variant is the default.
            </p>
            <Button type="button" size="sm" class="self-start" @click="openCreateVariantDialog">
              <Plus class="size-4" />
              Add Variant
            </Button>
          </div>

          <div
            v-if="variants.length === 0"
            class="rounded-lg border border-border bg-muted/10 px-4 py-12 text-center text-sm text-muted-foreground"
          >
            No variants yet. Add the first color variant of this article.
          </div>

          <div v-else class="overflow-hidden rounded-lg border border-border">
            <div class="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Image</TableHead>
                    <TableHead>Name</TableHead>
                    <TableHead>Inside</TableHead>
                    <TableHead>Outside</TableHead>
                    <TableHead>Default</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead class="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow v-for="(variant, index) in variants" :key="variant.key">
                    <TableCell class="whitespace-nowrap">
                      <img
                        v-if="variant.exampleImageFilename"
                        :src="variantExampleImageUrl('MUG', variant.exampleImageFilename, 200)"
                        :alt="`Example image of ${variant.name}`"
                        class="size-10 rounded-md border border-border bg-muted/20 object-contain"
                        data-testid="variant-example-image-thumbnail"
                      />
                      <span v-else class="text-muted-foreground">—</span>
                    </TableCell>
                    <TableCell class="min-w-32 font-medium text-foreground">
                      {{ variant.name }}
                      <p
                        v-if="variantErrors[index]"
                        class="text-sm font-normal text-destructive"
                        data-testid="variant-field-error"
                      >
                        {{ variantErrors[index] }}
                      </p>
                    </TableCell>
                    <TableCell class="whitespace-nowrap text-muted-foreground">
                      <span class="inline-flex items-center gap-2">
                        <span
                          v-if="isValidColor(variant.insideColorCode)"
                          class="size-4 rounded-full border border-border"
                          :style="{ backgroundColor: variant.insideColorCode }"
                        />
                        {{ variant.insideColorCode }}
                      </span>
                    </TableCell>
                    <TableCell class="whitespace-nowrap text-muted-foreground">
                      <span class="inline-flex items-center gap-2">
                        <span
                          v-if="isValidColor(variant.outsideColorCode)"
                          class="size-4 rounded-full border border-border"
                          :style="{ backgroundColor: variant.outsideColorCode }"
                        />
                        {{ variant.outsideColorCode }}
                      </span>
                    </TableCell>
                    <TableCell class="whitespace-nowrap">
                      <Badge v-if="variant.isDefault" variant="success">Default</Badge>
                      <span v-else class="text-muted-foreground">—</span>
                    </TableCell>
                    <TableCell class="whitespace-nowrap">
                      <Badge :variant="variant.active ? 'success' : 'muted'">
                        {{ variant.active ? 'Active' : 'Inactive' }}
                      </Badge>
                    </TableCell>
                    <TableCell class="whitespace-nowrap text-right">
                      <div class="inline-flex items-center gap-1">
                        <Button
                          type="button"
                          variant="outline"
                          size="icon-sm"
                          :aria-label="`Edit variant ${variant.name}`"
                          @click="openEditVariantDialog(variant)"
                        >
                          <Pencil class="size-4" />
                        </Button>
                        <Button
                          type="button"
                          variant="outline"
                          size="icon-sm"
                          :aria-label="`Remove variant ${variant.name}`"
                          @click="removeVariant(variant)"
                        >
                          <Trash2 class="size-4" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </div>
          </div>

          <p class="text-sm text-muted-foreground">
            Variant changes are applied when the article is saved. This list is the complete state:
            a removed variant is deleted together with its example image.
          </p>
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
          <RouterLink :to="{ name: 'admin-articles', query: route.query }">Cancel</RouterLink>
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
            :description="`This permanently deletes ${general.name || 'this article'} including its mug details and variants. This action cannot be undone.`"
            confirm-label="Delete Article"
            :deleting="isDeleting"
            confirm-test-id="confirm-delete-article"
            @confirm="deleteCurrentArticle"
          />
        </template>
      </div>
    </Card>

    <AdminArticleMugVariantDialog
      v-model:open="isVariantDialogOpen"
      :variant="editingVariant"
      :is-only-variant="variantDialogLocksDefault"
      @save="saveVariant"
    />
  </section>
</template>
