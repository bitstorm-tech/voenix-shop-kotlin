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
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import type { AdminJob, SpodReleaseBody } from '@/stores/admin/fulfillment'

/**
 * Releases a SPOD job the submission quarantined after two creations nobody knows the outcome of.
 * Releasing starts a paid production, so the dialog keeps the three guards of the SPOD runbook: the
 * admin chooses what the partner's backoffice shows, types the job's reference by hand, and the
 * backend records the release with the admin's user id. The submit stays disabled until the typed
 * reference matches and the decision is complete.
 */
interface Props {
  job: AdminJob | null
  submitting?: boolean
  error?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  submitting: false,
  error: null,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'confirm', body: SpodReleaseBody): void
}>()

const decision = ref<'NOT_CREATED' | 'CREATED' | null>(null)
const externalReference = ref('')
const confirmation = ref('')

/** The reference the partner's backoffice shows for this job, and the one to type. */
const reference = computed(() =>
  props.job === null ? '' : `ORD-${props.job.orderId}-JOB-${props.job.jobId}`,
)

const canSubmit = computed(
  () =>
    !props.submitting &&
    props.job !== null &&
    confirmation.value === reference.value &&
    (decision.value === 'NOT_CREATED' ||
      (decision.value === 'CREATED' && externalReference.value.trim() !== '')),
)

watch(open, (isOpen) => {
  if (isOpen) {
    decision.value = null
    externalReference.value = ''
    confirmation.value = ''
  }
})

function confirmRelease() {
  if (!canSubmit.value) {
    return
  }

  emit(
    'confirm',
    decision.value === 'CREATED'
      ? {
          decision: 'CREATED',
          externalReference: externalReference.value.trim(),
          confirmation: confirmation.value,
        }
      : { decision: 'NOT_CREATED', confirmation: confirmation.value },
  )
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-lg rounded-xl">
      <DialogHeader>
        <DialogTitle>Release {{ reference }}</DialogTitle>
        <DialogDescription>
          Twice in a row nobody learned whether the partner created this order. Search the partner's
          backoffice for {{ reference }} first: releasing starts a paid production, and a wrong
          order id confirms somebody else's order.
        </DialogDescription>
      </DialogHeader>

      <form class="space-y-5" @submit.prevent="confirmRelease">
        <Alert v-if="error" variant="destructive">{{ error }}</Alert>

        <div class="space-y-2">
          <Label>What the backoffice shows</Label>
          <RadioGroup
            :model-value="decision ?? undefined"
            class="flex flex-col items-start gap-2"
            :disabled="submitting"
            @update:model-value="decision = $event as 'NOT_CREATED' | 'CREATED'"
          >
            <RadioGroupItem value="NOT_CREATED" data-testid="release-not-created">
              No order with this reference: create it again
            </RadioGroupItem>
            <RadioGroupItem value="CREATED" data-testid="release-created">
              Exactly one order: adopt its id and confirm it
            </RadioGroupItem>
          </RadioGroup>
        </div>

        <div v-if="decision === 'CREATED'" class="space-y-2">
          <Label for="release-external-reference">The partner's order id</Label>
          <Input
            id="release-external-reference"
            v-model="externalReference"
            type="text"
            maxlength="128"
            :disabled="submitting"
          />
        </div>

        <div class="space-y-2">
          <Label for="release-confirmation">Type {{ reference }} to confirm</Label>
          <Input
            id="release-confirmation"
            v-model="confirmation"
            type="text"
            autocomplete="off"
            :disabled="submitting"
          />
        </div>

        <DialogFooter class="gap-2">
          <Button type="button" variant="outline" :disabled="submitting" @click="open = false">
            Cancel
          </Button>
          <Button type="submit" :disabled="!canSubmit" data-testid="confirm-release">
            {{ submitting ? 'Releasing...' : 'Release job' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
