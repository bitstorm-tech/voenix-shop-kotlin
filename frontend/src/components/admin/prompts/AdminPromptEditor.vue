<script setup lang="ts">
import { ArrowLeft, Copy } from 'lucide-vue-next'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import AdminPriceEditor from '@/components/admin/pricing/AdminPriceEditor.vue'
import AdminPromptForm from '@/components/admin/prompts/AdminPromptForm.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { PROMPT_EDITOR_TABS, useAdminPromptEdit } from '@/composables/useAdminPromptEdit'

const props = defineProps<{
  promptId: number | null
}>()

const { t } = useI18n()
const route = useRoute()
const editor = useAdminPromptEdit(props.promptId)
const pageTitle = computed(() => {
  const title = editor.form.title.trim()
  if (editor.isCreate) {
    return title === ''
      ? t('admin.prompts.editor.createTitle')
      : t('admin.prompts.editor.createTitleWithName', { title })
  }

  return title === ''
    ? t('admin.prompts.editor.title')
    : t('admin.prompts.editor.titleWithName', { title })
})

const saveDisabled = computed(() => editor.isSaveBlocked.value)
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader :title="pageTitle">
      <template #actions>
        <Button as-child variant="outline">
          <RouterLink :to="{ name: 'admin-prompts', query: route.query }">
            <ArrowLeft class="size-4" />
            {{ t('admin.prompts.editor.back') }}
          </RouterLink>
        </Button>
      </template>
    </AdminPageHeader>

    <Card
      v-if="editor.isLoading.value"
      class="px-4 py-16 text-center text-sm text-muted-foreground"
      data-testid="prompt-editor-loading"
    >
      {{ t('admin.prompts.editor.loading') }}
    </Card>

    <Card v-else-if="editor.loadError.value" class="space-y-4 p-5">
      <Alert variant="destructive">
        <p class="font-medium">
          {{
            t(
              editor.isNotFound.value
                ? 'admin.prompts.editor.errors.notFoundTitle'
                : 'admin.prompts.editor.errors.detailTitle',
            )
          }}
        </p>
        <p class="mt-1">{{ editor.loadError.value }}</p>
      </Alert>
      <div class="flex flex-wrap gap-2">
        <Button v-if="!editor.isNotFound.value" type="button" @click="editor.reload">
          {{ t('admin.prompts.editor.retry') }}
        </Button>
        <Button type="button" variant="outline" @click="editor.cancel">
          {{ t('admin.prompts.editor.back') }}
        </Button>
      </div>
    </Card>

    <form v-else class="min-w-0" @submit.prevent="editor.save">
      <Card class="min-w-0 overflow-hidden">
        <Tabs v-model="editor.activeTab.value" class="min-w-0 p-4 sm:p-6">
          <TabsList class="grid w-full grid-cols-2 sm:w-auto sm:min-w-72">
            <TabsTrigger :value="PROMPT_EDITOR_TABS.prompt">
              {{ t('admin.prompts.editor.tabs.prompt') }}
            </TabsTrigger>
            <TabsTrigger :value="PROMPT_EDITOR_TABS.price">
              {{ t('admin.prompts.editor.tabs.price') }}
            </TabsTrigger>
          </TabsList>

          <Alert v-if="editor.saveError.value" variant="destructive" class="mt-4">
            {{ editor.saveError.value }}
          </Alert>

          <div v-if="editor.hasReferenceError.value" class="mt-4 space-y-3">
            <Alert v-if="editor.categoryReferenceError.value" variant="destructive">
              <p class="font-medium">{{ t('admin.prompts.editor.errors.categoriesTitle') }}</p>
              <p class="mt-1">{{ editor.categoryReferenceError.value }}</p>
              <Button
                type="button"
                variant="outline"
                class="mt-3"
                :disabled="editor.categoriesStore.isLoading"
                @click="editor.retryCategoryReferences"
              >
                {{ t('admin.prompts.editor.retry') }}
              </Button>
            </Alert>
            <Alert v-if="editor.slotReferenceError.value" variant="destructive">
              <p class="font-medium">{{ t('admin.prompts.editor.errors.slotsTitle') }}</p>
              <p class="mt-1">{{ editor.slotReferenceError.value }}</p>
              <Button
                type="button"
                variant="outline"
                class="mt-3"
                :disabled="editor.slotsStore.isLoading"
                @click="editor.retrySlotReferences"
              >
                {{ t('admin.prompts.editor.retry') }}
              </Button>
            </Alert>
            <Alert v-if="editor.vatReferenceError.value" variant="destructive">
              <p class="font-medium">{{ t('admin.prompts.editor.errors.vatTitle') }}</p>
              <p class="mt-1">{{ editor.vatReferenceError.value }}</p>
              <Button
                type="button"
                variant="outline"
                class="mt-3"
                :disabled="editor.vatStore.isLoading"
                @click="editor.retryVatReferences"
              >
                {{ t('admin.prompts.editor.retry') }}
              </Button>
            </Alert>
          </div>

          <TabsContent :value="PROMPT_EDITOR_TABS.prompt" class="mt-6 min-w-0">
            <AdminPromptForm
              :form="editor.form"
              :field-errors="editor.fieldErrors"
              :categories="editor.categoriesStore.categories"
              :subcategories="editor.categoriesStore.subcategories"
              :loading-references="editor.categoriesStore.isLoading"
              :disabled="editor.isSaving.value"
              :upload-example-image="editor.uploadExampleImage"
              @title-change="editor.setTitle"
              @prompt-text-change="editor.setPromptText"
              @llm-change="editor.setLlm"
              @example-image-selection="editor.markExampleImageSelectionDirty"
              @example-image-filename-change="editor.setExampleImageFilename"
              @category-id-change="editor.setCategoryId"
              @subcategory-id-change="editor.setSubcategoryId"
              @active-change="editor.setActive"
              @archived-change="editor.setArchived"
              @slot-variant-ids-change="editor.setSlotVariantIds"
              @uploading-change="editor.setUploadingImage"
            />
          </TabsContent>

          <TabsContent :value="PROMPT_EDITOR_TABS.price" class="mt-6">
            <AdminPriceEditor
              :description="
                t(
                  editor.isCreate
                    ? 'admin.prompts.editor.createPriceDescription'
                    : 'admin.prompts.editor.priceDescription',
                )
              "
              :form="editor.price.form"
              :fields="editor.price.fields"
              :price="editor.price.lastCalculatedPrice.value"
              :vat-options="editor.priceVatOptions.value"
              :is-loading="editor.price.isLoading.value"
              :is-calculating="editor.price.isCalculating.value"
              :setup-error="editor.price.setupError.value"
              :error="editor.price.error.value"
              :input-error="editor.price.inputError.value"
              :disabled="editor.isSaving.value"
              :retry-label="t('admin.prompts.editor.retry')"
              @retry-setup="editor.retryPriceInitialization"
              @retry-calculation="editor.price.calculateNow"
              @purchase-vat-change="editor.price.setPurchaseVatId"
              @sales-vat-change="editor.price.setSalesVatId"
              @purchase-mode-change="editor.price.setPurchaseCalculationMode"
              @sales-mode-change="editor.price.setSalesCalculationMode"
              @purchase-active-row-change="editor.price.setPurchaseActiveRow"
              @sales-active-row-change="editor.price.setSalesActiveRow"
              @purchase-price-change="editor.price.setPurchasePrice"
              @purchase-cost-change="editor.price.setPurchaseCost"
              @purchase-cost-percent-change="editor.price.setPurchaseCostPercent"
              @sales-margin-change="editor.price.setSalesMargin"
              @sales-margin-percent-change="editor.price.setSalesMarginPercent"
              @sales-total-change="editor.price.setSalesTotal"
            />
          </TabsContent>
        </Tabs>

        <div
          class="sticky bottom-0 z-10 flex flex-col-reverse gap-2 border-t border-border bg-background/95 px-4 py-4 backdrop-blur sm:flex-row sm:justify-end sm:px-6"
        >
          <Button
            type="button"
            variant="outline"
            class="sm:mr-auto"
            :disabled="!editor.canCopyFullPrompt.value"
            data-testid="prompt-editor-copy-full-prompt"
            @click="editor.copyFullPrompt"
          >
            <Copy class="size-4" />
            {{ t('admin.prompts.editor.copy.label') }}
          </Button>
          <Button
            type="button"
            variant="outline"
            :disabled="editor.isSaving.value"
            @click="editor.cancel"
          >
            {{ t('admin.prompts.editor.cancel') }}
          </Button>
          <Button type="submit" :disabled="saveDisabled">
            {{
              editor.isSaving.value
                ? t(
                    editor.isCreate
                      ? 'admin.prompts.editor.creating'
                      : 'admin.prompts.editor.saving',
                  )
                : t(editor.isCreate ? 'admin.prompts.editor.create' : 'admin.prompts.editor.save')
            }}
          </Button>
        </div>
      </Card>
    </form>
  </section>
</template>
