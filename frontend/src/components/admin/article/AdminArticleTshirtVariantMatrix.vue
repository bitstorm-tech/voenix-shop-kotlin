<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { ColorInput } from '@/components/ui/color-input'
import { FileInput } from '@/components/ui/file-input'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import { InvalidArticleRequestError } from '@/stores/admin/articles'
import { useAdminTshirtArticlesStore } from '@/stores/admin/tshirtArticles'
import {
  generateTshirtVariantMatrix,
  parseMatrixColors,
  parseMatrixSizes,
  type TshirtVariantRow,
  withSingleDefault,
} from './tshirtVariantMatrix'

/**
 * The variant table of a t-shirt, and the generator that fills it.
 *
 * A shirt has one row per colour and size, which is a product and not a list: typing twelve rows by
 * hand is how a colour ends up missing a size. An admin therefore types the two lists and the SPOD
 * product type once and generates the matrix; what stays per row is the pair of ids only the printer
 * knows — the appearance and the size — plus the picture, the default flag, and visibility.
 *
 * The rows are the **complete intended state** of `tshirtVariants`: a row with an id updates that
 * stored variant, a row without one inserts, and a stored variant no row mentions is deleted with
 * its example image when the article is saved.
 */
interface Props {
  rows: TshirtVariantRow[]
  /** Backend messages of one submitted variant, keyed by its index in `tshirtVariants`. */
  variantErrors?: Record<number, string>
  /** The message the backend reported for the array as a whole. */
  listError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  variantErrors: () => ({}),
  listError: null,
})

const emit = defineEmits<{
  (event: 'update:rows', rows: TshirtVariantRow[]): void
}>()

const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024
const ACCEPTED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']

const articlesStore = useAdminTshirtArticlesStore()

const colorsText = ref('')
const sizesText = ref('')
const productTypeIdText = ref<string | number>('')
const generatorError = shallowRef<string | null>(null)
const imageError = shallowRef<string | null>(null)
const uploadingRowKey = shallowRef<number | null>(null)

let keySequence = 0

/**
 * The generator inputs are pre-filled from the rows the editor loaded, so that regenerating an
 * existing shirt starts from what it already is instead of from an empty form.
 */
watch(
  () => props.rows,
  (rows) => {
    if (rows.length === 0 || colorsText.value !== '' || sizesText.value !== '') {
      return
    }

    const colors = new Map<string, string>()
    const sizes: string[] = []
    for (const row of rows) {
      if (!colors.has(row.colorName)) colors.set(row.colorName, row.colorHex)
      if (!sizes.includes(row.sizeLabel)) sizes.push(row.sizeLabel)
    }

    colorsText.value = [...colors].map(([name, hex]) => `${name} ${hex}`).join('\n')
    sizesText.value = sizes.join(', ')
    productTypeIdText.value = String(rows[0]?.spodProductTypeId ?? '')
  },
  { immediate: true },
)

const colors = computed(() => parseMatrixColors(colorsText.value))
const sizes = computed(() => parseMatrixSizes(sizesText.value))
const plannedRowCount = computed(() => colors.value.length * sizes.value.length)

/** The one product type of the article. The backend refuses an array whose rows disagree. */
const productTypeId = computed(() => {
  const parsed = Number(String(productTypeIdText.value).trim())
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
})

function nextKey() {
  keySequence += 1
  return -keySequence
}

function emitRows(rows: TshirtVariantRow[]) {
  emit('update:rows', rows)
}

function generate() {
  generatorError.value = null

  if (productTypeId.value === null) {
    generatorError.value = 'The SPOD product type id is required and must be a positive number.'
    return
  }

  if (plannedRowCount.value === 0) {
    generatorError.value = 'Enter at least one color and one size.'
    return
  }

  emitRows(
    generateTshirtVariantMatrix(
      { colors: colors.value, sizes: sizes.value, spodProductTypeId: productTypeId.value },
      props.rows,
      nextKey,
    ),
  )
}

function patchRow(key: number, patch: Partial<TshirtVariantRow>) {
  emitRows(props.rows.map((row) => (row.key === key ? { ...row, ...patch } : row)))
}

function parseId(value: string | number) {
  const parsed = Number(String(value).trim())
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

function setAppearanceId(key: number, value: string | number) {
  patchRow(key, { spodAppearanceId: parseId(value) })
}

function setSizeId(key: number, value: string | number) {
  patchRow(key, { spodSizeId: parseId(value) })
}

function setColorHex(key: number, value: string) {
  patchRow(key, { colorHex: value })
}

function setActive(key: number, value: boolean) {
  patchRow(key, { active: value })
}

function makeDefault(key: number) {
  emitRows(withSingleDefault(props.rows, key))
}

function removeRow(key: number) {
  emitRows(withSingleDefault(props.rows.filter((row) => row.key !== key)))
}

function exampleImageUrl(filename: string) {
  return variantExampleImageUrl('TSHIRT', filename, 200)
}

/**
 * Stores one row's mockup photo before the article that names it is written. The upload is a route
 * of its own — the write body only carries the file name it answers.
 */
async function uploadExampleImage(key: number, files: File[]) {
  const file = files[0]
  imageError.value = null

  if (!file) {
    return
  }

  if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) {
    imageError.value = 'Example image must be a PNG, JPEG, or WebP file.'
    return
  }

  if (file.size > MAX_IMAGE_SIZE_BYTES) {
    imageError.value = 'Example image must not exceed 10 MB.'
    return
  }

  uploadingRowKey.value = key

  try {
    const filename = await articlesStore.uploadVariantExampleImage(file)
    patchRow(key, { exampleImageFilename: filename })
  } catch (error) {
    // A rejected pre-upload is a `400` whose message sits on the `file` field of the request.
    imageError.value =
      error instanceof InvalidArticleRequestError
        ? (error.fieldError('file') ?? error.message)
        : error instanceof Error
          ? error.message
          : 'Failed to upload the example image.'
  } finally {
    uploadingRowKey.value = null
  }
}
</script>

<template>
  <div class="space-y-5">
    <Alert v-if="props.listError" variant="destructive">{{ props.listError }}</Alert>

    <fieldset class="space-y-4 rounded-lg border border-border bg-muted/10 p-4">
      <legend class="px-1 text-base font-semibold text-foreground">Generate the matrix</legend>
      <p class="text-sm text-muted-foreground">
        Every variant of one shirt is the same garment, so all rows share one SPOD product type. The
        colors and sizes are multiplied into rows; the appearance and size ids are looked up per row
        afterwards.
      </p>

      <div class="grid gap-4 md:grid-cols-3">
        <FormField label="Colors (one per line, Name #rrggbb)" for="tshirt-matrix-colors">
          <Textarea
            id="tshirt-matrix-colors"
            v-model="colorsText"
            rows="4"
            placeholder="Black #000000&#10;White #ffffff"
            data-testid="tshirt-matrix-colors"
          />
        </FormField>
        <FormField label="Sizes" for="tshirt-matrix-sizes">
          <Input
            id="tshirt-matrix-sizes"
            v-model="sizesText"
            type="text"
            placeholder="S, M, L, XL, XXL"
            data-testid="tshirt-matrix-sizes"
          />
        </FormField>
        <FormField label="SPOD product type id" for="tshirt-matrix-product-type">
          <Input
            id="tshirt-matrix-product-type"
            v-model="productTypeIdText"
            type="number"
            min="1"
            step="1"
            data-testid="tshirt-matrix-product-type"
          />
        </FormField>
      </div>

      <div class="flex flex-wrap items-center gap-3">
        <Button type="button" size="sm" data-testid="tshirt-matrix-generate" @click="generate">
          Generate {{ plannedRowCount }} variants
        </Button>
        <p class="text-sm text-muted-foreground">
          Rows that already exist keep their ids, their picture, and their lookups. A pair the new
          matrix does not contain is deleted when the article is saved.
        </p>
      </div>

      <p v-if="generatorError" class="text-sm text-destructive" data-testid="tshirt-matrix-error">
        {{ generatorError }}
      </p>
    </fieldset>

    <div
      v-if="props.rows.length === 0"
      class="rounded-lg border border-border bg-muted/10 px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No variants yet. Enter the colors and sizes above and generate the matrix.
    </div>

    <div v-else class="overflow-hidden rounded-lg border border-border">
      <div class="overflow-x-auto">
        <Table class="min-w-[56rem]">
          <TableHeader>
            <TableRow>
              <TableHead>Image</TableHead>
              <TableHead>Color</TableHead>
              <TableHead>Size</TableHead>
              <TableHead>Appearance id</TableHead>
              <TableHead>Size id</TableHead>
              <TableHead>Default</TableHead>
              <TableHead>Active</TableHead>
              <TableHead class="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="(row, index) in props.rows" :key="row.key">
              <TableCell class="whitespace-nowrap">
                <div class="flex items-center gap-2">
                  <img
                    v-if="row.exampleImageFilename"
                    :src="exampleImageUrl(row.exampleImageFilename)"
                    :alt="`Example image of ${row.colorName} ${row.sizeLabel}`"
                    class="size-10 rounded-md border border-border bg-muted/20 object-contain"
                    data-testid="tshirt-variant-image"
                  />
                  <FileInput
                    accept="image/png,image/jpeg,image/webp"
                    reset-on-select
                    size="sm"
                    variant="outline"
                    :disabled="uploadingRowKey === row.key"
                    :button-test-id="`tshirt-variant-image-upload-${index}`"
                    @change="uploadExampleImage(row.key, $event)"
                  >
                    {{ uploadingRowKey === row.key ? 'Uploading...' : 'Image' }}
                  </FileInput>
                </div>
              </TableCell>
              <TableCell class="min-w-40">
                <div class="flex items-center gap-2">
                  <ColorInput
                    :model-value="row.colorHex"
                    :label="`Color of ${row.colorName}`"
                    @update:model-value="setColorHex(row.key, $event)"
                  />
                  <span class="text-foreground">{{ row.colorName }}</span>
                </div>
                <p
                  v-if="props.variantErrors[index]"
                  class="text-sm text-destructive"
                  data-testid="tshirt-variant-error"
                >
                  {{ props.variantErrors[index] }}
                </p>
              </TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ row.sizeLabel }}
              </TableCell>
              <TableCell class="w-32">
                <Input
                  :model-value="row.spodAppearanceId ?? ''"
                  type="number"
                  min="1"
                  step="1"
                  :aria-label="`SPOD appearance id of ${row.colorName} ${row.sizeLabel}`"
                  :data-testid="`tshirt-variant-appearance-${index}`"
                  @update:model-value="setAppearanceId(row.key, $event)"
                />
              </TableCell>
              <TableCell class="w-32">
                <Input
                  :model-value="row.spodSizeId ?? ''"
                  type="number"
                  min="1"
                  step="1"
                  :aria-label="`SPOD size id of ${row.colorName} ${row.sizeLabel}`"
                  :data-testid="`tshirt-variant-size-${index}`"
                  @update:model-value="setSizeId(row.key, $event)"
                />
              </TableCell>
              <TableCell class="whitespace-nowrap">
                <Badge v-if="row.isDefault" variant="success">Default</Badge>
                <Button
                  v-else
                  type="button"
                  variant="outline"
                  size="sm"
                  :data-testid="`tshirt-variant-default-${index}`"
                  @click="makeDefault(row.key)"
                >
                  Make default
                </Button>
              </TableCell>
              <TableCell class="whitespace-nowrap">
                <Checkbox
                  :model-value="row.active"
                  :aria-label="`Active ${row.colorName} ${row.sizeLabel}`"
                  @update:model-value="setActive(row.key, $event === true)"
                />
              </TableCell>
              <TableCell class="whitespace-nowrap text-right">
                <Button
                  type="button"
                  variant="outline"
                  size="icon-sm"
                  :aria-label="`Remove variant ${row.colorName} ${row.sizeLabel}`"
                  @click="removeRow(row.key)"
                >
                  <Trash2 class="size-4" />
                </Button>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </div>
    </div>

    <p v-if="imageError" class="text-sm text-destructive">{{ imageError }}</p>
  </div>
</template>
