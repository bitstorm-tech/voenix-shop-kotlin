<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { ApiError } from '@/lib/api'
import type { AdminSupplierDto } from '@/stores/admin/suppliers'
import {
  type SupplierLogin,
  SupplierLoginEmailTakenError,
  SupplierLoginInvitationNotDeliveredError,
  SupplierLoginNotFoundError,
  SupplierLoginUnknownSupplierError,
  useAdminSupplierLoginsStore,
} from '@/stores/admin/supplierLogins'

interface Props {
  supplier: AdminSupplierDto | null
}

const props = defineProps<Props>()

const open = defineModel<boolean>('open', { required: true })

const loginsStore = useAdminSupplierLoginsStore()

const email = ref('')
const emailError = ref<string | null>(null)
const generalError = ref<string | null>(null)
/**
 * The `502` case, which is neither a success nor a failed create: the login exists, only its
 * invitation did not arrive. It is a warning that stays on screen until the dialog is reopened.
 */
const undeliveredInvitation = ref<string | null>(null)
const loginToDelete = ref<SupplierLogin | null>(null)
const isDeleteDialogOpen = ref(false)

const listError = computed(() =>
  loginsStore.error === null
    ? null
    : loginsStore.error.message || 'The logins could not be loaded.',
)

const isDeleting = computed(() => loginsStore.deletingUserId !== null)

watch(open, (isOpen) => {
  email.value = ''
  emailError.value = null
  generalError.value = null
  undeliveredInvitation.value = null
  loginToDelete.value = null
  isDeleteDialogOpen.value = false

  if (isOpen && props.supplier !== null) {
    void loginsStore.fetchLogins(props.supplier.id)
  }
})

async function createLogin() {
  const supplier = props.supplier
  if (supplier === null || loginsStore.isCreating) {
    return
  }

  emailError.value = null
  generalError.value = null
  undeliveredInvitation.value = null

  try {
    await loginsStore.createLogin(supplier.id, email.value)
    email.value = ''
  } catch (error) {
    await handleCreateError(error)
  }
}

async function handleCreateError(error: unknown) {
  if (error instanceof SupplierLoginInvitationNotDeliveredError) {
    // The row exists — reloading is what makes it visible, because the create call threw before it
    // could add it to the list.
    email.value = ''
    undeliveredInvitation.value =
      'The login was created, but its invitation e-mail could not be delivered. There is no way to send it again: either the invited person requests a link with "Forgot password" on the login page, or you delete this login and create it once more.'
    await reloadLogins()
    return
  }

  if (error instanceof SupplierLoginEmailTakenError) {
    emailError.value = 'This e-mail address already belongs to an account.'
    return
  }

  if (error instanceof SupplierLoginUnknownSupplierError) {
    generalError.value = `${error.message}. Reload the supplier list.`
    return
  }

  if (error instanceof ApiError && error.status === 400) {
    emailError.value = error.fieldErrors.email?.[0] ?? null
    generalError.value =
      emailError.value === null ? error.message || 'Invalid e-mail address.' : null
    return
  }

  generalError.value =
    error instanceof Error && error.message !== ''
      ? error.message
      : 'The login could not be created.'
}

function askToDelete(login: SupplierLogin) {
  loginToDelete.value = login
  isDeleteDialogOpen.value = true
}

async function deleteLogin() {
  const login = loginToDelete.value
  if (login === null) {
    return
  }

  generalError.value = null

  try {
    await loginsStore.deleteLogin(login.userId)
  } catch (error) {
    generalError.value =
      error instanceof SupplierLoginNotFoundError
        ? 'This login no longer exists. The list has been reloaded.'
        : error instanceof Error && error.message !== ''
          ? error.message
          : 'The login could not be deleted.'
    await reloadLogins()
  } finally {
    isDeleteDialogOpen.value = false
    loginToDelete.value = null
  }
}

async function reloadLogins() {
  if (props.supplier !== null) {
    await loginsStore.fetchLogins(props.supplier.id)
  }
}

/** The creation timestamp is an ISO instant; the date is all an operator needs. */
function createdDate(login: SupplierLogin): string {
  return login.createdAt.slice(0, 10)
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-2xl rounded-xl">
      <DialogHeader>
        <DialogTitle>Logins for {{ supplier?.name ?? 'this supplier' }}</DialogTitle>
        <DialogDescription>
          Everybody listed here can sign in to the supplier portal and see this supplier's
          production jobs. A new login receives an invitation e-mail with a link to set its
          password.
        </DialogDescription>
      </DialogHeader>

      <div class="space-y-5">
        <Alert v-if="generalError" variant="destructive">{{ generalError }}</Alert>
        <Alert v-if="undeliveredInvitation" variant="warning">{{ undeliveredInvitation }}</Alert>
        <Alert v-if="listError" variant="destructive">{{ listError }}</Alert>

        <p v-else-if="loginsStore.isLoading" class="text-sm text-muted-foreground">
          Loading logins...
        </p>

        <p v-else-if="loginsStore.logins.length === 0" class="text-sm text-muted-foreground">
          This supplier has no login yet.
        </p>

        <Table v-else>
          <TableHeader>
            <TableRow>
              <TableHead>Email</TableHead>
              <TableHead>Created</TableHead>
              <TableHead class="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="login in loginsStore.logins" :key="login.userId">
              <TableCell class="min-w-48 text-foreground">{{ login.email }}</TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ createdDate(login) }}
              </TableCell>
              <TableCell class="whitespace-nowrap text-right">
                <Button
                  variant="outline"
                  size="icon-sm"
                  :aria-label="`Delete login ${login.email}`"
                  :title="`Delete login ${login.email}`"
                  :disabled="isDeleting"
                  @click="askToDelete(login)"
                >
                  <Trash2 class="size-4" />
                  <span class="sr-only">Delete</span>
                </Button>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>

        <form class="space-y-2 border-t border-border pt-5" @submit.prevent="createLogin">
          <Label for="supplier-login-email">Email</Label>
          <div class="flex flex-wrap items-start gap-2">
            <Input
              id="supplier-login-email"
              v-model="email"
              type="email"
              required
              autocomplete="off"
              placeholder="name@example.com"
              class="min-w-48 flex-1"
              :disabled="loginsStore.isCreating"
              :aria-invalid="emailError ? true : undefined"
            />
            <Button type="submit" :disabled="loginsStore.isCreating">
              {{ loginsStore.isCreating ? 'Creating...' : 'Create login' }}
            </Button>
          </div>
          <p v-if="emailError" class="text-sm text-destructive">{{ emailError }}</p>
          <p v-else class="text-sm text-muted-foreground">
            The address receives the invitation and is the user name of the login.
          </p>
        </form>
      </div>

      <ConfirmDeleteDialog
        v-model:open="isDeleteDialogOpen"
        title="Delete login?"
        :description="`This permanently deletes the login ${loginToDelete?.email ?? ''} and revokes its access immediately — the next request it makes is refused. This action cannot be undone.`"
        confirm-label="Delete login"
        :deleting="isDeleting"
        confirm-test-id="confirm-delete-supplier-login"
        @confirm="deleteLogin"
      />

      <DialogFooter>
        <Button type="button" variant="outline" @click="open = false">Close</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
