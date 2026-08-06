<script setup lang="ts">
import { computed, reactive } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { CheckboxCard } from '@/components/ui/checkbox-card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useDialogForm } from '@/composables/useDialogForm'
import { useFormErrors } from '@/composables/useFormErrors'
import type {
  AdminPromotionDto,
  PromotionDiscountType,
  UpsertAdminPromotionRequest,
} from '@/stores/admin/promotions'

interface Props {
  promotion: AdminPromotionDto | null
  saving?: boolean
  deleting?: boolean
  couponCodeError?: string | null
  generalError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  saving: false,
  deleting: false,
  couponCodeError: null,
  generalError: null,
})
const { t } = useI18n()

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'save', payload: UpsertAdminPromotionRequest): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const MAX_NAME_LENGTH = 255
const MAX_COUPON_CODE_LENGTH = 64
const MAX_PERCENTAGE_DISCOUNT = 100

interface FormState {
  name: string
  discountType: PromotionDiscountType
  discountValue: string
  couponCode: string
  startsAt: string
  endsAt: string
  usageLimitTotal: string
  usageLimitPerUser: string
  isActive: boolean
}

const form = reactive<FormState>({
  name: '',
  discountType: 'PERCENTAGE',
  discountValue: '',
  couponCode: '',
  startsAt: '',
  endsAt: '',
  usageLimitTotal: '',
  usageLimitPerUser: '',
  isActive: true,
})
const { fieldErrors, clearFieldErrors } = useFormErrors<
  | 'name'
  | 'discountValue'
  | 'couponCode'
  | 'startsAt'
  | 'endsAt'
  | 'usageLimitTotal'
  | 'usageLimitPerUser'
>()

const isEditMode = computed(() => props.promotion !== null)
const isReadOnly = computed(() => props.promotion?.isLocked ?? false)
const configurationLocked = computed(() => isReadOnly.value)
const title = computed(() => {
  if (isReadOnly.value) {
    return t('admin.promotions.dialog.detailsTitle')
  }

  return t(
    isEditMode.value ? 'admin.promotions.dialog.editTitle' : 'admin.promotions.dialog.newTitle',
  )
})
const couponCodeErrorMessage = computed(() => fieldErrors.couponCode ?? props.couponCodeError)
const discountValueLabel = computed(() =>
  t(
    form.discountType === 'PERCENTAGE'
      ? 'admin.promotions.dialog.discountPercent'
      : 'admin.promotions.dialog.discountFixed',
  ),
)
const discountValueHint = computed(() =>
  t(
    form.discountType === 'PERCENTAGE'
      ? 'admin.promotions.dialog.percentageHint'
      : 'admin.promotions.dialog.fixedHint',
  ),
)

const discountTypeSelectValue = computed({
  get: () => form.discountType,
  set: (value: string) => {
    form.discountType = value as PromotionDiscountType
    fieldErrors.discountValue = undefined
    emit('clearErrors')
  },
})

function resetForm() {
  form.name = props.promotion?.name ?? ''
  form.discountType = props.promotion?.discount.discountType ?? 'PERCENTAGE'
  form.discountValue = formatDiscountInput(props.promotion)
  form.couponCode = props.promotion?.couponCode ?? ''
  form.startsAt = toDateTimeLocalValue(props.promotion?.startsAt ?? null)
  form.endsAt = toDateTimeLocalValue(props.promotion?.endsAt ?? null)
  form.usageLimitTotal = props.promotion?.usageLimitTotal?.toString() ?? ''
  form.usageLimitPerUser = props.promotion?.usageLimitPerUser?.toString() ?? ''
  form.isActive = props.promotion?.isActive ?? true
  clearFieldErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.promotion],
  resetForm,
})

function updateName(value: string | number) {
  form.name = String(value)
  fieldErrors.name = undefined
  emit('clearErrors')
}

function updateCouponCode(value: string | number) {
  form.couponCode = String(value)
  fieldErrors.couponCode = undefined
  emit('clearErrors')
}

function updateDiscountValue(value: string | number) {
  form.discountValue = String(value)
  fieldErrors.discountValue = undefined
}

function validate() {
  clearFieldErrors()
  let ok = true

  if (form.name.trim() === '') {
    fieldErrors.name = t('admin.promotions.dialog.validation.nameRequired')
    ok = false
  } else if (form.name.trim().length > MAX_NAME_LENGTH) {
    fieldErrors.name = t('admin.promotions.dialog.validation.nameTooLong', {
      max: MAX_NAME_LENGTH,
    })
    ok = false
  }

  if (form.couponCode.trim() === '') {
    fieldErrors.couponCode = t('admin.promotions.dialog.validation.codeRequired')
    ok = false
  } else if (form.couponCode.trim().length > MAX_COUPON_CODE_LENGTH) {
    fieldErrors.couponCode = t('admin.promotions.dialog.validation.codeTooLong', {
      max: MAX_COUPON_CODE_LENGTH,
    })
    ok = false
  }

  if (parseDiscountValue() === null) {
    ok = false
  }

  if (!validateDateWindow()) {
    ok = false
  }
  if (parseOptionalLimit('usageLimitTotal') === undefined) {
    ok = false
  }
  if (parseOptionalLimit('usageLimitPerUser') === undefined) {
    ok = false
  }

  return ok
}

function parseDiscountValue() {
  const normalized = form.discountValue.trim().replace(',', '.')
  if (normalized === '') {
    fieldErrors.discountValue = t('admin.promotions.dialog.validation.discountRequired')
    return null
  }

  const value = Number(normalized)
  if (!Number.isFinite(value) || value <= 0) {
    fieldErrors.discountValue = t('admin.promotions.dialog.validation.discountPositive')
    return null
  }

  if (form.discountType === 'PERCENTAGE') {
    if (value > MAX_PERCENTAGE_DISCOUNT) {
      fieldErrors.discountValue = t('admin.promotions.dialog.validation.discountMaximum', {
        max: MAX_PERCENTAGE_DISCOUNT,
      })
      return null
    }

    return value
  }

  if (!/^\d+([.,]\d{1,2})?$/.test(form.discountValue.trim())) {
    fieldErrors.discountValue = t('admin.promotions.dialog.validation.fixedDecimals')
    return null
  }

  return Math.round(value * 100)
}

function validateDateWindow() {
  if (!form.startsAt || !form.endsAt) {
    return true
  }

  const startsAt = new Date(form.startsAt)
  const endsAt = new Date(form.endsAt)
  if (Number.isNaN(startsAt.getTime()) || Number.isNaN(endsAt.getTime())) {
    fieldErrors.startsAt = Number.isNaN(startsAt.getTime())
      ? t('admin.promotions.dialog.validation.invalidStart')
      : undefined
    fieldErrors.endsAt = Number.isNaN(endsAt.getTime())
      ? t('admin.promotions.dialog.validation.invalidEnd')
      : undefined
    return false
  }

  if (startsAt > endsAt) {
    fieldErrors.endsAt = t('admin.promotions.dialog.validation.endBeforeStart')
    return false
  }

  return true
}

function parseOptionalLimit(field: 'usageLimitTotal' | 'usageLimitPerUser') {
  const raw = form[field].trim()
  if (raw === '') {
    return null
  }

  const value = Number(raw)
  if (!Number.isInteger(value) || value <= 0) {
    fieldErrors[field] = t('admin.promotions.dialog.validation.limitPositiveInteger')
    return undefined
  }

  return value
}

function savePromotion() {
  if (props.saving || props.deleting) {
    return
  }

  if (isReadOnly.value) {
    const promotion = props.promotion
    if (!promotion) {
      return
    }

    // A locked promotion may only be switched on or off; every other field is sent back unchanged,
    // flattened from the nested response discount into the flat request shape.
    emit('save', {
      name: promotion.name,
      discountType: promotion.discount.discountType,
      discountValue: promotion.discount.discountValue,
      couponCode: promotion.couponCode,
      startsAt: promotion.startsAt,
      endsAt: promotion.endsAt,
      usageLimitTotal: promotion.usageLimitTotal,
      usageLimitPerUser: promotion.usageLimitPerUser,
      isActive: form.isActive,
    })
    return
  }

  if (!validate()) {
    return
  }

  const discountValue = parseDiscountValue()
  const usageLimitTotal = parseOptionalLimit('usageLimitTotal')
  const usageLimitPerUser = parseOptionalLimit('usageLimitPerUser')
  if (discountValue === null || usageLimitTotal === undefined || usageLimitPerUser === undefined) {
    return
  }

  emit('save', {
    name: form.name.trim(),
    discountType: form.discountType,
    discountValue,
    couponCode: form.couponCode.trim(),
    startsAt: fromDateTimeLocalValue(form.startsAt),
    endsAt: fromDateTimeLocalValue(form.endsAt),
    usageLimitTotal,
    usageLimitPerUser,
    isActive: form.isActive,
  })
}

function deletePromotion() {
  if (props.saving || props.deleting || isReadOnly.value) {
    return
  }

  emit('delete')
}

function formatDiscountInput(promotion: AdminPromotionDto | null) {
  if (!promotion) {
    return ''
  }

  const { discountType, discountValue } = promotion.discount
  if (discountType === 'FIXED_AMOUNT') {
    return (discountValue / 100).toFixed(2)
  }

  return discountValue.toString()
}

function toDateTimeLocalValue(value: string | null) {
  if (!value) {
    return ''
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }

  const localDate = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return localDate.toISOString().slice(0, 16)
}

function fromDateTimeLocalValue(value: string) {
  return value ? new Date(value).toISOString() : null
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-3xl rounded-xl">
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
        <DialogDescription class="sr-only">
          {{ t('admin.promotions.dialog.description') }}
        </DialogDescription>
      </DialogHeader>

      <form class="space-y-5" @submit.prevent="savePromotion">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <Alert v-if="isReadOnly" variant="info">
          {{ t('admin.promotions.dialog.lockedInfo') }}
        </Alert>

        <div class="grid gap-5 md:grid-cols-2">
          <FormField
            :label="t('admin.promotions.dialog.name')"
            for="promotion-name"
            :error="fieldErrors.name"
          >
            <Input
              id="promotion-name"
              :model-value="form.name"
              type="text"
              :placeholder="t('admin.promotions.dialog.namePlaceholder')"
              :maxlength="MAX_NAME_LENGTH"
              :disabled="saving || deleting || configurationLocked"
              :aria-invalid="fieldErrors.name ? true : undefined"
              @update:model-value="updateName"
            />
          </FormField>

          <FormField
            :label="t('admin.promotions.dialog.promotionCode')"
            for="promotion-coupon-code"
            :error="couponCodeErrorMessage"
          >
            <Input
              id="promotion-coupon-code"
              :model-value="form.couponCode"
              type="text"
              :placeholder="t('admin.promotions.dialog.promotionCodePlaceholder')"
              :maxlength="MAX_COUPON_CODE_LENGTH"
              :disabled="saving || deleting || configurationLocked"
              :aria-invalid="couponCodeErrorMessage ? true : undefined"
              @update:model-value="updateCouponCode"
            />
          </FormField>
        </div>

        <div class="grid gap-5 md:grid-cols-2">
          <FormField
            :label="t('admin.promotions.dialog.discountType')"
            for="promotion-discount-type"
          >
            <Select
              v-model="discountTypeSelectValue"
              :disabled="saving || deleting || configurationLocked"
            >
              <SelectTrigger id="promotion-discount-type">
                <SelectValue :placeholder="t('admin.promotions.dialog.selectDiscountType')" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="PERCENTAGE">
                  {{ t('admin.promotions.dialog.percentage') }}
                </SelectItem>
                <SelectItem value="FIXED_AMOUNT">
                  {{ t('admin.promotions.dialog.fixedAmount') }}
                </SelectItem>
              </SelectContent>
            </Select>
          </FormField>

          <FormField
            :label="discountValueLabel"
            for="promotion-discount-value"
            :error="fieldErrors.discountValue"
            :hint="discountValueHint"
          >
            <Input
              id="promotion-discount-value"
              :model-value="form.discountValue"
              type="number"
              inputmode="decimal"
              min="0"
              :max="form.discountType === 'PERCENTAGE' ? MAX_PERCENTAGE_DISCOUNT : undefined"
              step="0.01"
              :placeholder="form.discountType === 'PERCENTAGE' ? '10' : '5.00'"
              :disabled="saving || deleting || configurationLocked"
              :aria-invalid="fieldErrors.discountValue ? true : undefined"
              @update:model-value="updateDiscountValue"
            />
          </FormField>
        </div>

        <div class="grid gap-5 md:grid-cols-2">
          <FormField
            :label="t('admin.promotions.dialog.startsAt')"
            for="promotion-starts-at"
            :error="fieldErrors.startsAt"
          >
            <Input
              id="promotion-starts-at"
              v-model="form.startsAt"
              type="datetime-local"
              :disabled="saving || deleting || configurationLocked"
              :aria-invalid="fieldErrors.startsAt ? true : undefined"
            />
          </FormField>

          <FormField
            :label="t('admin.promotions.dialog.endsAt')"
            for="promotion-ends-at"
            :error="fieldErrors.endsAt"
          >
            <Input
              id="promotion-ends-at"
              v-model="form.endsAt"
              type="datetime-local"
              :disabled="saving || deleting || configurationLocked"
              :aria-invalid="fieldErrors.endsAt ? true : undefined"
            />
          </FormField>
        </div>

        <div class="grid gap-5 md:grid-cols-2">
          <FormField
            :label="t('admin.promotions.dialog.totalUsageLimit')"
            for="promotion-total-limit"
            :error="fieldErrors.usageLimitTotal"
          >
            <Input
              id="promotion-total-limit"
              v-model="form.usageLimitTotal"
              type="number"
              inputmode="numeric"
              min="1"
              step="1"
              :placeholder="t('admin.promotions.dialog.unlimited')"
              :disabled="saving || deleting || configurationLocked"
              :aria-invalid="fieldErrors.usageLimitTotal ? true : undefined"
            />
          </FormField>

          <FormField
            :label="t('admin.promotions.dialog.perUserUsageLimit')"
            for="promotion-user-limit"
            :error="fieldErrors.usageLimitPerUser"
          >
            <Input
              id="promotion-user-limit"
              v-model="form.usageLimitPerUser"
              type="number"
              inputmode="numeric"
              min="1"
              step="1"
              :placeholder="t('admin.promotions.dialog.unlimited')"
              :disabled="saving || deleting || configurationLocked"
              :aria-invalid="fieldErrors.usageLimitPerUser ? true : undefined"
            />
          </FormField>
        </div>

        <div class="grid gap-4 md:grid-cols-2">
          <CheckboxCard
            id="promotion-is-active"
            v-model="form.isActive"
            class="bg-muted/20"
            content-class="block space-y-1.5"
            :disabled="saving || deleting"
          >
            <span class="block font-medium text-foreground">
              {{ t('admin.promotions.dialog.active') }}
            </span>
            <span class="block text-sm leading-6 text-muted-foreground">
              {{ t('admin.promotions.dialog.activeDescription') }}
            </span>
          </CheckboxCard>

          <div class="rounded-lg border border-border bg-muted/20 px-4 py-3">
            <p class="text-sm font-medium text-foreground">
              {{ t('admin.promotions.dialog.redemptions') }}
            </p>
            <p class="mt-1 text-sm text-muted-foreground">
              {{ promotion?.redemptionCount ?? 0 }}
            </p>
          </div>
        </div>

        <DialogFooter class="gap-2 border-t border-border pt-5">
          <template v-if="isEditMode && !isReadOnly">
            <Button
              type="button"
              variant="destructive"
              class="sm:mr-auto"
              :disabled="saving || deleting"
              @click="isDeleteDialogOpen = true"
            >
              <Trash2 class="size-4" />
              {{ t('admin.promotions.dialog.delete') }}
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              :title="t('admin.promotions.dialog.deleteTitle')"
              :description="
                t('admin.promotions.dialog.deleteDescription', {
                  name: form.name || t('admin.promotions.dialog.unnamedPromotion'),
                })
              "
              :confirm-label="t('admin.promotions.dialog.delete')"
              :deleting="deleting"
              confirm-test-id="confirm-delete-promotion"
              @confirm="deletePromotion"
            />
          </template>

          <Button
            type="button"
            variant="outline"
            :disabled="saving || deleting"
            @click="open = false"
          >
            {{ t(isReadOnly ? 'admin.promotions.dialog.close' : 'admin.promotions.dialog.cancel') }}
          </Button>
          <Button type="submit" :disabled="saving || deleting">
            {{
              t(
                saving
                  ? 'admin.promotions.dialog.saving'
                  : isReadOnly
                    ? 'admin.promotions.dialog.saveActiveState'
                    : 'admin.promotions.dialog.save',
              )
            }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
