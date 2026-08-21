<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Address } from '@/stores/shop/checkout'
import type { Country } from '@/stores/shop/countries'
import {
  composePhoneNumber,
  createDialCodeOptions,
  getDefaultDialCode,
  getDialCode,
  getPhoneNumberPart,
  hasExplicitDialCode,
} from '@/lib/phoneNumber'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import AddressCountryField from '@/components/shop/AddressCountryField.vue'

const props = withDefaults(
  defineProps<{
    modelValue: Address
    idPrefix: string
    /** The shippable countries; they feed the dropdown and always the dial-code list. */
    countryOptions: Country[]
    /** `select` for a shipping country, `text` for the unrestricted billing country. */
    countryMode?: 'select' | 'text'
    /** Server-side message for the country field, rendered inline. */
    countryError?: string | null
    showEmail?: boolean
    showPhone?: boolean
    /**
     * Whether the phone number is mandatory. It is for a cart that contains a t-shirt: the shirt is
     * shipped by the print-on-demand partner, who needs a number to deliver, and the backend
     * refuses such a checkout without one (issue #205).
     */
    phoneRequired?: boolean
    /** Message for the phone field, rendered inline the way the country error is. */
    phoneError?: string | null
  }>(),
  {
    countryMode: 'select',
    countryError: null,
    showEmail: false,
    showPhone: false,
    phoneRequired: false,
    phoneError: null,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: Address]
}>()

const { t } = useI18n()

const address = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const phoneErrorId = computed(() => `${props.idPrefix}-phone-error`)
const phoneDescribedBy = computed(() => (props.phoneError ? phoneErrorId.value : undefined))

const dialCodeOptions = computed(() => createDialCodeOptions(props.countryOptions))
const selectedDialCode = shallowRef(
  getDialCode(props.modelValue.phone, props.modelValue.country, dialCodeOptions.value),
)
const phoneNumberPart = computed(() =>
  getPhoneNumberPart(props.modelValue.phone, dialCodeOptions.value),
)

watch(
  () => [props.modelValue.phone, props.modelValue.country, dialCodeOptions.value] as const,
  ([phone, country]) => {
    selectedDialCode.value = getDialCode(phone, country, dialCodeOptions.value)
  },
  { immediate: true },
)

function update(field: keyof Address, value: string) {
  emit('update:modelValue', { ...props.modelValue, [field]: value })
}

function updateCountry(country: string) {
  const currentPhone = props.modelValue.phone
  const nextAddress = { ...props.modelValue, country }
  const defaultDialCode = getDefaultDialCode(country, dialCodeOptions.value)

  if (currentPhone && !hasExplicitDialCode(currentPhone)) {
    nextAddress.phone = composePhoneNumber(defaultDialCode, currentPhone)
    selectedDialCode.value = defaultDialCode
  } else if (!currentPhone) {
    selectedDialCode.value = defaultDialCode
  }

  emit('update:modelValue', nextAddress)
}

function updateDialCode(dialCode: string) {
  selectedDialCode.value = dialCode
  update(
    'phone',
    composePhoneNumber(dialCode, getPhoneNumberPart(props.modelValue.phone, dialCodeOptions.value)),
  )
}

function updatePhoneNumber(number: string) {
  update('phone', composePhoneNumber(selectedDialCode.value, number))
}
</script>

<template>
  <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
    <!-- First Name -->
    <div class="flex flex-col gap-2">
      <Label :for="`${idPrefix}-firstName`">{{ t('checkout.address.firstName') }}</Label>
      <Input
        :id="`${idPrefix}-firstName`"
        :model-value="address.firstName"
        required
        autocomplete="given-name"
        @update:model-value="update('firstName', $event as string)"
      />
    </div>

    <!-- Last Name -->
    <div class="flex flex-col gap-2">
      <Label :for="`${idPrefix}-lastName`">{{ t('checkout.address.lastName') }}</Label>
      <Input
        :id="`${idPrefix}-lastName`"
        :model-value="address.lastName"
        required
        autocomplete="family-name"
        @update:model-value="update('lastName', $event as string)"
      />
    </div>

    <!-- Street -->
    <div class="flex flex-col gap-2">
      <Label :for="`${idPrefix}-street`">{{ t('checkout.address.street') }}</Label>
      <Input
        :id="`${idPrefix}-street`"
        :model-value="address.street"
        required
        autocomplete="address-line1"
        @update:model-value="update('street', $event as string)"
      />
    </div>

    <!-- House Number -->
    <div class="flex flex-col gap-2">
      <Label :for="`${idPrefix}-houseNumber`">{{ t('checkout.address.houseNumber') }}</Label>
      <Input
        :id="`${idPrefix}-houseNumber`"
        :model-value="address.houseNumber"
        required
        autocomplete="address-line2"
        @update:model-value="update('houseNumber', $event as string)"
      />
    </div>

    <!-- Postal Code -->
    <div class="flex flex-col gap-2">
      <Label :for="`${idPrefix}-postalCode`">{{ t('checkout.address.postalCode') }}</Label>
      <Input
        :id="`${idPrefix}-postalCode`"
        :model-value="address.postalCode"
        required
        autocomplete="postal-code"
        @update:model-value="update('postalCode', $event as string)"
      />
    </div>

    <!-- City -->
    <div class="flex flex-col gap-2">
      <Label :for="`${idPrefix}-city`">{{ t('checkout.address.city') }}</Label>
      <Input
        :id="`${idPrefix}-city`"
        :model-value="address.city"
        required
        autocomplete="address-level2"
        @update:model-value="update('city', $event as string)"
      />
    </div>

    <!-- Country -->
    <AddressCountryField
      :id="`${idPrefix}-country`"
      :mode="countryMode"
      :model-value="address.country"
      :options="countryOptions"
      :error-message="countryError"
      @update:model-value="updateCountry"
    />

    <!-- Email (conditional) -->
    <div v-if="showEmail" class="flex flex-col gap-2">
      <Label :for="`${idPrefix}-email`">{{ t('checkout.address.email') }}</Label>
      <Input
        :id="`${idPrefix}-email`"
        type="email"
        :model-value="address.email"
        required
        autocomplete="email"
        @update:model-value="update('email', $event as string)"
      />
    </div>

    <!-- Phone -->
    <div v-if="showPhone" class="flex flex-col gap-2 sm:col-span-2">
      <Label :for="`${idPrefix}-phone`">
        {{ t(phoneRequired ? 'checkout.address.phoneRequired' : 'checkout.address.phone') }}
      </Label>
      <div
        class="flex h-9 overflow-hidden rounded-md border bg-transparent shadow-sm transition-colors focus-within:ring-1 focus-within:ring-ring"
        :class="phoneError ? 'border-destructive' : 'border-input'"
      >
        <Select
          :model-value="selectedDialCode"
          :disabled="dialCodeOptions.length === 0"
          @update:model-value="updateDialCode(String($event))"
        >
          <SelectTrigger
            :id="`${idPrefix}-phone-prefix`"
            class="h-full w-[6.25rem] rounded-none border-0 border-r border-input px-2 shadow-none focus:ring-0"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem
              v-for="option in dialCodeOptions"
              :key="option.countryCode"
              :value="option.dialCode"
            >
              <span class="inline-flex items-center gap-2">
                <span aria-hidden="true">{{ option.flag }}</span>
                <span>{{ option.label }}</span>
              </span>
            </SelectItem>
          </SelectContent>
        </Select>
        <Input
          :id="`${idPrefix}-phone`"
          type="tel"
          :model-value="phoneNumberPart"
          :required="phoneRequired"
          :aria-describedby="phoneDescribedBy"
          autocomplete="tel-national"
          class="h-full rounded-none border-0 shadow-none focus-visible:ring-0"
          @update:model-value="updatePhoneNumber($event as string)"
        />
      </div>
      <p v-if="phoneError" :id="phoneErrorId" role="alert" class="text-sm text-destructive">
        {{ phoneError }}
      </p>
      <p v-else-if="phoneRequired" class="text-xs text-muted-foreground">
        {{ t('checkout.address.phoneRequiredHint') }}
      </p>
    </div>
  </div>
</template>
