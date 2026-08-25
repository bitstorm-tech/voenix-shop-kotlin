<script setup lang="ts">
import { computed, reactive } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogFooter,
  DialogHeader,
  DialogContent,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PasswordInput } from '@/components/ui/password-input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useDialogForm } from '@/composables/useDialogForm'
import { optionalText } from '@/lib/forms'
import type { AdminSupplierDto } from '@/stores/admin/suppliers'
import {
  type AdminProductionDestinationDto,
  type ProductionChannel,
  PRODUCTION_CHANNELS,
  type SaveProductionDestinationRequest,
  type SpodEnvironment,
  SPOD_ENVIRONMENTS,
} from '@/stores/admin/productionDestinations'

interface Props {
  destination: AdminProductionDestinationDto | null
  loading?: boolean
  saving?: boolean
  deleting?: boolean
  suppliers: readonly Readonly<AdminSupplierDto>[]
  suppliersLoading?: boolean
  generalError?: string | null
  /** Backend messages of a rejected write, keyed by the JSON path of the offending value. */
  fieldErrors?: Record<string, string>
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  saving: false,
  deleting: false,
  suppliersLoading: false,
  generalError: null,
  fieldErrors: () => ({}),
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'save', payload: SaveProductionDestinationRequest): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const NONE_SUPPLIER_VALUE = 'none'
const DEFAULT_SFTP_PORT = '22'
const DEFAULT_REMOTE_PATH = '/'
const DEFAULT_TIMEOUT_SECONDS = '30'

const CHANNEL_LABELS: Record<ProductionChannel, string> = {
  SFTP: 'SFTP upload',
  SPOD: 'SPOD (print-on-demand API)',
}

const ENVIRONMENT_LABELS: Record<SpodEnvironment, string> = {
  STAGING: 'Staging',
  PRODUCTION: 'Production',
}

interface FormState {
  supplierId: number | null
  channel: ProductionChannel
  label: string
  enabled: boolean
  notificationEmail: string
  notificationName: string
  sftpHost: string
  sftpPort: string
  sftpUsername: string
  /** Write-only. Never filled from a response — a stored password is not readable. */
  sftpPassword: string
  sftpHostKeyFingerprint: string
  sftpRemotePath: string
  sftpTimeoutSeconds: string
  spodEnvironment: SpodEnvironment
  /** Write-only, exactly like the SFTP password above. */
  spodAccessToken: string
  spodTimeoutSeconds: string
}

const form = reactive<FormState>({
  supplierId: null,
  channel: 'SFTP',
  label: '',
  enabled: true,
  notificationEmail: '',
  notificationName: '',
  sftpHost: '',
  sftpPort: DEFAULT_SFTP_PORT,
  sftpUsername: '',
  sftpPassword: '',
  sftpHostKeyFingerprint: '',
  sftpRemotePath: DEFAULT_REMOTE_PATH,
  sftpTimeoutSeconds: DEFAULT_TIMEOUT_SECONDS,
  spodEnvironment: 'STAGING',
  spodAccessToken: '',
  spodTimeoutSeconds: DEFAULT_TIMEOUT_SECONDS,
})

const localErrors = reactive<Record<string, string>>({})

const isEditMode = computed(() => props.destination !== null || props.loading)
const dialogTitle = computed(() =>
  isEditMode.value ? 'Edit Production Destination' : 'New Production Destination',
)

/**
 * A stored secret can be kept, but only on an existing destination: a create has nothing to keep,
 * so the field is required there and the hint says which of the two situations the user is in.
 */
const secretHint = computed(() =>
  props.destination === null
    ? 'Required. It is stored write-only and never shown again.'
    : 'Leave empty to keep the stored value.',
)

function errorFor(path: string): string | null {
  return localErrors[path] ?? props.fieldErrors[path] ?? null
}

const supplierSelectValue = computed({
  get: () => form.supplierId?.toString() ?? NONE_SUPPLIER_VALUE,
  set: (value: string) => {
    form.supplierId = value === NONE_SUPPLIER_VALUE ? null : Number(value)
    clearError('supplierId')
  },
})

const channelSelectValue = computed({
  get: () => form.channel,
  set: (value: string) => {
    form.channel = value === 'SPOD' ? 'SPOD' : 'SFTP'
    clearError('channel')
  },
})

const environmentSelectValue = computed({
  get: () => form.spodEnvironment,
  set: (value: string) => {
    form.spodEnvironment = value === 'PRODUCTION' ? 'PRODUCTION' : 'STAGING'
    clearError('spod.environment')
  },
})

function resetForm() {
  const destination = props.destination

  form.supplierId = destination?.supplierId ?? null
  form.channel = destination?.channel ?? 'SFTP'
  form.label = destination?.label ?? ''
  form.enabled = destination?.enabled ?? true
  form.notificationEmail = destination?.notificationEmail ?? ''
  form.notificationName = destination?.notificationName ?? ''

  form.sftpHost = destination?.sftp?.host ?? ''
  form.sftpPort = destination?.sftp?.port?.toString() ?? DEFAULT_SFTP_PORT
  form.sftpUsername = destination?.sftp?.username ?? ''
  form.sftpHostKeyFingerprint = destination?.sftp?.hostKeyFingerprint ?? ''
  form.sftpRemotePath = destination?.sftp?.remotePath ?? DEFAULT_REMOTE_PATH
  form.sftpTimeoutSeconds = destination?.sftp?.timeoutSeconds?.toString() ?? DEFAULT_TIMEOUT_SECONDS

  form.spodEnvironment = destination?.spod?.environment ?? 'STAGING'
  form.spodTimeoutSeconds = destination?.spod?.timeoutSeconds?.toString() ?? DEFAULT_TIMEOUT_SECONDS

  // The two secrets are set here and nowhere else: a response never carries them, so re-opening a
  // stored destination starts with empty fields rather than a mask of something unknown.
  form.sftpPassword = ''
  form.spodAccessToken = ''

  clearLocalErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.destination?.id, props.loading],
  resetForm,
})

function clearLocalErrors() {
  for (const key of Object.keys(localErrors)) {
    delete localErrors[key]
  }
}

function clearError(path: string) {
  delete localErrors[path]
  emit('clearErrors')
}

function requiredText(path: string, label: string, value: string) {
  if (value.trim() === '') {
    localErrors[path] = `${label} is required.`
  }
}

function requiredTimeout(path: string, value: string) {
  const parsed = Number(value.trim())
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 3600) {
    localErrors[path] = 'Timeout must be a whole number of seconds between 1 and 3600.'
  }
}

function validate(): boolean {
  clearLocalErrors()

  if (form.supplierId === null) {
    localErrors.supplierId = 'Supplier is required.'
  }
  requiredText('label', 'Label', form.label)

  if (form.channel === 'SFTP') {
    requiredText('sftp.host', 'Host', form.sftpHost)
    requiredText('sftp.username', 'Username', form.sftpUsername)
    requiredText('sftp.hostKeyFingerprint', 'Host key fingerprint', form.sftpHostKeyFingerprint)
    requiredTimeout('sftp.timeoutSeconds', form.sftpTimeoutSeconds)
    if (props.destination === null) {
      requiredText('sftp.password', 'Password', form.sftpPassword)
    }
  } else {
    requiredTimeout('spod.timeoutSeconds', form.spodTimeoutSeconds)
    if (props.destination === null) {
      requiredText('spod.accessToken', 'Access token', form.spodAccessToken)
    }
  }

  return Object.keys(localErrors).length === 0
}

/**
 * The submitted secret, or `undefined` when the field was left empty on an existing destination —
 * an omitted secret is what tells the backend to keep the stored one.
 */
function submittedSecret(value: string): string | undefined {
  return value === '' ? undefined : value
}

function buildPayload(): SaveProductionDestinationRequest {
  const payload: SaveProductionDestinationRequest = {
    supplierId: form.supplierId as number,
    channel: form.channel,
    label: form.label.trim(),
    enabled: form.enabled,
    notificationEmail: optionalText(form.notificationEmail),
    notificationName: optionalText(form.notificationName),
  }

  if (form.channel === 'SFTP') {
    payload.sftp = {
      host: form.sftpHost.trim(),
      port: Number(form.sftpPort.trim() || DEFAULT_SFTP_PORT),
      username: form.sftpUsername.trim(),
      password: submittedSecret(form.sftpPassword),
      hostKeyFingerprint: form.sftpHostKeyFingerprint.trim(),
      remotePath: optionalText(form.sftpRemotePath),
      timeoutSeconds: Number(form.sftpTimeoutSeconds.trim()),
    }
    return payload
  }

  payload.spod = {
    environment: form.spodEnvironment,
    accessToken: submittedSecret(form.spodAccessToken),
    timeoutSeconds: Number(form.spodTimeoutSeconds.trim()),
  }
  return payload
}

function saveDestination() {
  if (props.saving || props.deleting || props.loading || !validate()) {
    return
  }

  emit('save', buildPayload())
}

function deleteDestination() {
  if (props.saving || props.deleting || props.loading) {
    return
  }

  emit('delete')
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-3xl rounded-xl">
      <DialogHeader>
        <DialogTitle>{{ dialogTitle }}</DialogTitle>
      </DialogHeader>

      <div v-if="loading" class="px-4 py-12 text-center text-sm text-muted-foreground">
        Loading destination...
      </div>

      <form v-else class="space-y-6" @submit.prevent="saveDestination">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <div class="grid gap-4 md:grid-cols-2">
          <FormField
            label="Supplier"
            for="destination-supplier"
            :error="errorFor('supplierId')"
            :hint="suppliersLoading ? 'Loading suppliers...' : undefined"
          >
            <Select v-model="supplierSelectValue" :disabled="suppliersLoading">
              <SelectTrigger id="destination-supplier" data-testid="destination-supplier">
                <SelectValue placeholder="Select supplier" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem :value="NONE_SUPPLIER_VALUE">No supplier</SelectItem>
                <SelectItem
                  v-for="supplier in suppliers"
                  :key="supplier.id"
                  :value="supplier.id.toString()"
                >
                  {{ supplier.name }}
                </SelectItem>
              </SelectContent>
            </Select>
          </FormField>

          <FormField
            label="Channel"
            for="destination-channel"
            :error="errorFor('channel')"
            :hint="
              isEditMode
                ? 'The channel is fixed once a destination exists. Create a new destination to use another channel.'
                : 'The channel decides which account this destination needs.'
            "
          >
            <Select v-model="channelSelectValue" :disabled="isEditMode">
              <SelectTrigger id="destination-channel" data-testid="destination-channel">
                <SelectValue placeholder="Select channel" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="channel in PRODUCTION_CHANNELS" :key="channel" :value="channel">
                  {{ CHANNEL_LABELS[channel] }}
                </SelectItem>
              </SelectContent>
            </Select>
          </FormField>

          <FormField label="Label" for="destination-label" :error="errorFor('label')">
            <Input
              id="destination-label"
              v-model="form.label"
              type="text"
              placeholder="e.g. Acme production upload"
              data-testid="destination-label"
              :aria-invalid="errorFor('label') ? true : undefined"
            />
          </FormField>

          <div class="flex items-center gap-3 md:pt-7">
            <Checkbox id="destination-enabled" v-model="form.enabled" />
            <Label for="destination-enabled">Enabled</Label>
          </div>
        </div>

        <fieldset class="space-y-4 border-t border-border pt-5">
          <legend class="text-base font-semibold text-foreground">Notification</legend>
          <div class="grid gap-4 md:grid-cols-2">
            <FormField
              label="Notification email"
              for="destination-notification-email"
              :error="errorFor('notificationEmail')"
              hint="Optional. Who the producer notification goes to."
            >
              <Input
                id="destination-notification-email"
                v-model="form.notificationEmail"
                type="email"
                :aria-invalid="errorFor('notificationEmail') ? true : undefined"
              />
            </FormField>
            <FormField
              label="Notification name"
              for="destination-notification-name"
              :error="errorFor('notificationName')"
            >
              <Input
                id="destination-notification-name"
                v-model="form.notificationName"
                type="text"
              />
            </FormField>
          </div>
        </fieldset>

        <fieldset
          v-if="form.channel === 'SFTP'"
          class="space-y-4 border-t border-border pt-5"
          data-testid="destination-sftp-form"
        >
          <legend class="text-base font-semibold text-foreground">SFTP account</legend>
          <div class="grid gap-4 md:grid-cols-2">
            <FormField label="Host" for="destination-sftp-host" :error="errorFor('sftp.host')">
              <Input
                id="destination-sftp-host"
                v-model="form.sftpHost"
                type="text"
                data-testid="destination-sftp-host"
                :aria-invalid="errorFor('sftp.host') ? true : undefined"
              />
            </FormField>
            <FormField label="Port" for="destination-sftp-port" :error="errorFor('sftp.port')">
              <Input
                id="destination-sftp-port"
                v-model="form.sftpPort"
                type="number"
                inputmode="numeric"
                min="1"
                max="65535"
                step="1"
                :aria-invalid="errorFor('sftp.port') ? true : undefined"
              />
            </FormField>
            <FormField
              label="Username"
              for="destination-sftp-username"
              :error="errorFor('sftp.username')"
            >
              <Input
                id="destination-sftp-username"
                v-model="form.sftpUsername"
                type="text"
                autocomplete="off"
                :aria-invalid="errorFor('sftp.username') ? true : undefined"
              />
            </FormField>
            <FormField
              label="Password"
              for="destination-sftp-password"
              :error="errorFor('sftp.password')"
              :hint="secretHint"
            >
              <PasswordInput
                id="destination-sftp-password"
                v-model="form.sftpPassword"
                autocomplete="new-password"
                data-testid="destination-sftp-password"
                :aria-invalid="errorFor('sftp.password') ? true : undefined"
              />
            </FormField>
            <FormField
              label="Host key fingerprint"
              for="destination-sftp-fingerprint"
              :error="errorFor('sftp.hostKeyFingerprint')"
            >
              <Input
                id="destination-sftp-fingerprint"
                v-model="form.sftpHostKeyFingerprint"
                type="text"
                :aria-invalid="errorFor('sftp.hostKeyFingerprint') ? true : undefined"
              />
            </FormField>
            <FormField
              label="Remote path"
              for="destination-sftp-remote-path"
              :error="errorFor('sftp.remotePath')"
            >
              <Input
                id="destination-sftp-remote-path"
                v-model="form.sftpRemotePath"
                type="text"
                :aria-invalid="errorFor('sftp.remotePath') ? true : undefined"
              />
            </FormField>
            <FormField
              label="Timeout (seconds)"
              for="destination-sftp-timeout"
              :error="errorFor('sftp.timeoutSeconds')"
            >
              <Input
                id="destination-sftp-timeout"
                v-model="form.sftpTimeoutSeconds"
                type="number"
                inputmode="numeric"
                min="1"
                max="3600"
                step="1"
                :aria-invalid="errorFor('sftp.timeoutSeconds') ? true : undefined"
              />
            </FormField>
          </div>
        </fieldset>

        <fieldset
          v-else
          class="space-y-4 border-t border-border pt-5"
          data-testid="destination-spod-form"
        >
          <legend class="text-base font-semibold text-foreground">SPOD account</legend>
          <div class="grid gap-4 md:grid-cols-2">
            <FormField
              label="Environment"
              for="destination-spod-environment"
              :error="errorFor('spod.environment')"
            >
              <Select v-model="environmentSelectValue">
                <SelectTrigger
                  id="destination-spod-environment"
                  data-testid="destination-spod-environment"
                >
                  <SelectValue placeholder="Select environment" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem
                    v-for="environment in SPOD_ENVIRONMENTS"
                    :key="environment"
                    :value="environment"
                  >
                    {{ ENVIRONMENT_LABELS[environment] }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </FormField>
            <FormField
              label="Timeout (seconds)"
              for="destination-spod-timeout"
              :error="errorFor('spod.timeoutSeconds')"
            >
              <Input
                id="destination-spod-timeout"
                v-model="form.spodTimeoutSeconds"
                type="number"
                inputmode="numeric"
                min="1"
                max="3600"
                step="1"
                data-testid="destination-spod-timeout"
                :aria-invalid="errorFor('spod.timeoutSeconds') ? true : undefined"
              />
            </FormField>
            <FormField
              label="Access token"
              for="destination-spod-access-token"
              class="md:col-span-2"
              :error="errorFor('spod.accessToken')"
              :hint="secretHint"
            >
              <PasswordInput
                id="destination-spod-access-token"
                v-model="form.spodAccessToken"
                autocomplete="off"
                label="Show access token"
                data-testid="destination-spod-access-token"
                :aria-invalid="errorFor('spod.accessToken') ? true : undefined"
              />
            </FormField>
          </div>
        </fieldset>

        <DialogFooter class="gap-2 border-t border-border pt-5">
          <template v-if="destination">
            <Button
              type="button"
              variant="destructive"
              class="sm:mr-auto"
              :disabled="saving || deleting"
              @click="isDeleteDialogOpen = true"
            >
              <Trash2 class="size-4" />
              Delete Destination
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete production destination?"
              :description="`This permanently deletes ${form.label || 'this destination'} including its stored credentials. A destination that jobs still reference cannot be deleted — disable it instead.`"
              confirm-label="Delete Destination"
              :deleting="deleting"
              confirm-test-id="confirm-delete-destination"
              @confirm="deleteDestination"
            />
          </template>

          <Button
            type="button"
            variant="outline"
            :disabled="saving || deleting"
            @click="open = false"
          >
            Cancel
          </Button>
          <Button type="submit" :disabled="saving || deleting">
            {{ saving ? 'Saving...' : 'Save Destination' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
