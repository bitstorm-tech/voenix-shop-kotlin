<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type { ApiFieldErrors } from '@/lib/api'
import {
  orderNumber,
  SHIPPING_CARRIER_LABELS,
  SHIPPING_CARRIERS,
  type ShipJobPayload,
  type ShippingCarrier,
  type ShippableJob,
} from '@/stores/supplier/jobs'

/**
 * The dialog is shared by the two surfaces that report a shipment: a supplier for its own job, and
 * an administrator on a supplier's behalf. It therefore asks for the least it needs — the order the
 * job belongs to — so both job shapes fit, and takes the supplier name only as the optional extra
 * the admin surface has and the supplier surface has no reason to repeat.
 */
interface Props {
  job: ShippableJob | null
  supplierName?: string | null
  submitting?: boolean
  fieldErrors?: ApiFieldErrors
  generalError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  supplierName: null,
  submitting: false,
  fieldErrors: () => ({}),
  generalError: null,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'confirm', payload: ShipJobPayload): void
}>()

/** The select needs a value for "not stated"; the wire has no carrier at all in that case. */
const UNSPECIFIED_CARRIER = 'unspecified'

const carrier = ref<string>(UNSPECIFIED_CARRIER)
const trackingNumber = ref('')
/**
 * Guards against a second submit while the first one is still on the wire. Shipping is not
 * repeatable — the second attempt would answer `409 ALREADY_SHIPPED` — and the button's own
 * `disabled` only takes effect after the parent has re-rendered with `submitting`.
 */
const hasSubmitted = ref(false)

const title = computed(() =>
  props.job ? `Ship ${orderNumber(props.job.orderId)}` : 'Ship production job',
)
const carrierError = computed(() => props.fieldErrors.carrier?.[0] ?? null)
const trackingNumberError = computed(() => props.fieldErrors.trackingNumber?.[0] ?? null)
const isBusy = computed(() => props.submitting || hasSubmitted.value)

watch(open, (isOpen) => {
  if (isOpen) {
    carrier.value = UNSPECIFIED_CARRIER
    trackingNumber.value = ''
    hasSubmitted.value = false
  }
})

// The parent clears the field errors before each attempt; a new error means this attempt was
// answered, so the form has to become submittable again.
watch(
  () => props.fieldErrors,
  (errors) => {
    if (Object.keys(errors).length > 0) {
      hasSubmitted.value = false
    }
  },
)

watch(
  () => props.submitting,
  (submitting) => {
    if (!submitting) {
      hasSubmitted.value = false
    }
  },
)

function confirmShipment() {
  if (isBusy.value || props.job === null) {
    return
  }

  hasSubmitted.value = true
  emit('confirm', {
    carrier: carrier.value === UNSPECIFIED_CARRIER ? null : (carrier.value as ShippingCarrier),
    trackingNumber: trackingNumber.value.trim() === '' ? null : trackingNumber.value.trim(),
  })
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-lg rounded-xl">
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
        <DialogDescription>
          <template v-if="supplierName">
            You are reporting this shipment on behalf of {{ supplierName }}.
          </template>
          Marking this job as shipped cannot be undone, and the customer is notified by e-mail right
          away. Only confirm once the package is actually on its way.
        </DialogDescription>
      </DialogHeader>

      <form class="space-y-5" @submit.prevent="confirmShipment">
        <Alert v-if="generalError" variant="destructive">{{ generalError }}</Alert>

        <div class="space-y-2">
          <Label for="ship-carrier">Carrier</Label>
          <Select v-model="carrier" :disabled="isBusy">
            <SelectTrigger id="ship-carrier">
              <SelectValue placeholder="Select carrier" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem :value="UNSPECIFIED_CARRIER">Not stated</SelectItem>
              <SelectItem v-for="option in SHIPPING_CARRIERS" :key="option" :value="option">
                {{ SHIPPING_CARRIER_LABELS[option] }}
              </SelectItem>
            </SelectContent>
          </Select>
          <p v-if="carrierError" class="text-sm text-destructive">{{ carrierError }}</p>
          <p v-else class="text-sm text-muted-foreground">
            Optional. The tracking link of the customer's e-mail is built from the carrier.
          </p>
        </div>

        <div class="space-y-2">
          <Label for="ship-tracking-number">Tracking number</Label>
          <Input
            id="ship-tracking-number"
            v-model="trackingNumber"
            type="text"
            :disabled="isBusy"
            :aria-invalid="trackingNumberError ? true : undefined"
          />
          <p v-if="trackingNumberError" class="text-sm text-destructive">
            {{ trackingNumberError }}
          </p>
          <p v-else class="text-sm text-muted-foreground">
            Optional. Leave it empty if the package has no number.
          </p>
        </div>

        <DialogFooter class="gap-2">
          <Button type="button" variant="outline" :disabled="isBusy" @click="open = false">
            Cancel
          </Button>
          <Button type="submit" :disabled="isBusy" data-testid="confirm-ship">
            {{ isBusy ? 'Marking as shipped...' : 'Mark as shipped' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
