<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { Copy, KeyRound, RefreshCw, Trash2 } from 'lucide-vue-next'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { useToast } from '@/composables/useToast'
import { formatAdminStamp } from '@/lib/adminStamp'
import { ApiError } from '@/lib/api'
import {
  AccessTokenNotFoundError,
  useAdminAccessTokensStore,
  type AccessToken,
} from '@/stores/admin/accessTokens'

const tokensStore = useAdminAccessTokensStore()
const { toast } = useToast()

const name = shallowRef('')
const nameError = shallowRef<string | null>(null)
const createError = shallowRef<string | null>(null)
const revokeError = shallowRef<string | null>(null)
/** The token of the last create, shown once; component state only, gone with the page. */
const createdToken = shallowRef<string | null>(null)
const pending = ref<AccessToken | null>(null)

const endpointUrl = `${window.location.origin}/api/mcp`
const command = computed(
  () =>
    `claude mcp add --transport http voenix-admin ${endpointUrl} --header "Authorization: Bearer ${createdToken.value ?? ''}"`,
)

const listError = computed(() =>
  tokensStore.error === null
    ? null
    : tokensStore.error.message || 'The access tokens could not be loaded.',
)

const confirmOpen = computed({
  get: () => pending.value !== null,
  set: (open: boolean) => {
    if (!open && tokensStore.revokingId === null) pending.value = null
  },
})

async function createToken() {
  nameError.value = null
  createError.value = null
  try {
    const created = await tokensStore.createToken(name.value)
    createdToken.value = created.token
    name.value = ''
  } catch (err) {
    const fieldError = err instanceof ApiError ? err.fieldErrors.name?.[0] : undefined
    if (fieldError !== undefined) {
      nameError.value = fieldError
    } else {
      createError.value =
        err instanceof Error && err.message ? err.message : 'The token could not be created.'
    }
  }
}

async function copy(value: string, what: string) {
  try {
    await navigator.clipboard.writeText(value)
    toast({ title: `${what} copied`, variant: 'success' })
  } catch {
    toast({
      title: 'Copy failed',
      description: `The ${what.toLowerCase()} could not be copied to the clipboard.`,
      variant: 'destructive',
    })
  }
}

function askRevoke(token: AccessToken) {
  revokeError.value = null
  pending.value = token
}

async function confirmRevoke() {
  if (pending.value === null) return
  try {
    await tokensStore.revokeToken(pending.value.id)
  } catch (err) {
    if (err instanceof AccessTokenNotFoundError) {
      await tokensStore.fetchTokens()
    } else {
      revokeError.value =
        err instanceof Error && err.message ? err.message : 'The token could not be revoked.'
    }
  } finally {
    pending.value = null
  }
}

function reload() {
  revokeError.value = null
  void tokensStore.fetchTokens()
}

onMounted(reload)
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Access tokens" breakpoint="lg">
      <template #actions>
        <Button variant="outline" size="sm" :disabled="tokensStore.isLoading" @click="reload">
          <RefreshCw :class="['size-4', tokensStore.isLoading && 'animate-spin']" />
          Reload
        </Button>
      </template>
    </AdminPageHeader>

    <p class="text-sm text-muted-foreground">
      An access token lets an AI client such as Claude Code administer the catalog and master data
      through the MCP endpoint, acting as the admin who created it. It never reaches orders,
      production, logins, or these tokens. A token has no expiry; revoke it when it is no longer
      needed.
    </p>

    <Card class="p-4">
      <form class="flex flex-wrap items-start gap-3" @submit.prevent="createToken">
        <FormField label="Name" for="access-token-name" :error="nameError" class="min-w-64 flex-1">
          <Input
            id="access-token-name"
            v-model="name"
            type="text"
            placeholder="e.g. Claude Code on my laptop"
            :maxlength="100"
            :aria-invalid="nameError ? true : undefined"
          />
        </FormField>
        <Button type="submit" class="mt-6" :disabled="tokensStore.isCreating">
          <KeyRound class="size-4" />
          Create token
        </Button>
      </form>
    </Card>

    <Alert v-if="createError" variant="destructive">{{ createError }}</Alert>

    <Card v-if="createdToken" class="space-y-3 p-4" data-testid="created-token">
      <p class="font-medium">
        Copy the token now. It cannot be shown again; leaving or reloading this page drops it.
      </p>
      <div class="space-y-1">
        <p class="text-sm text-muted-foreground">Token</p>
        <div class="flex items-center gap-2">
          <code class="min-w-0 flex-1 rounded bg-muted px-2 py-1 text-sm break-all">{{
            createdToken
          }}</code>
          <Button variant="outline" size="sm" @click="copy(createdToken, 'Token')">
            <Copy class="size-4" />
            Copy
          </Button>
        </div>
      </div>
      <div class="space-y-1">
        <p class="text-sm text-muted-foreground">Endpoint URL</p>
        <code class="block rounded bg-muted px-2 py-1 text-sm break-all">{{ endpointUrl }}</code>
      </div>
      <div class="space-y-1">
        <p class="text-sm text-muted-foreground">Claude Code</p>
        <div class="flex items-center gap-2">
          <code class="min-w-0 flex-1 rounded bg-muted px-2 py-1 text-sm break-all">{{
            command
          }}</code>
          <Button variant="outline" size="sm" @click="copy(command, 'Command')">
            <Copy class="size-4" />
            Copy
          </Button>
        </div>
      </div>
      <Button
        variant="outline"
        size="sm"
        data-testid="close-created-token"
        @click="createdToken = null"
      >
        Done
      </Button>
    </Card>

    <Alert v-if="revokeError" variant="destructive">{{ revokeError }}</Alert>
    <Alert v-if="listError" variant="destructive">{{ listError }}</Alert>

    <!-- The table does not hang on the load failure: a token created after it still shows. -->
    <Card
      v-if="tokensStore.isLoading && tokensStore.tokens.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading access tokens...
    </Card>

    <Card
      v-else-if="tokensStore.tokens.length === 0 && !listError"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      There is no access token.
    </Card>

    <Card v-else-if="tokensStore.tokens.length > 0" class="overflow-hidden">
      <div class="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Owner</TableHead>
              <TableHead>Created</TableHead>
              <TableHead>Last used</TableHead>
              <TableHead class="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow
              v-for="token in tokensStore.tokens"
              :key="token.id"
              data-testid="access-token"
            >
              <TableCell class="font-medium">{{ token.name }}</TableCell>
              <TableCell class="text-muted-foreground">{{ token.userEmail }}</TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ formatAdminStamp(token.createdAt) }}
              </TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ formatAdminStamp(token.lastUsedAt) ?? 'never' }}
              </TableCell>
              <TableCell class="text-right">
                <Button
                  variant="outline"
                  size="sm"
                  :data-testid="`revoke-${token.id}`"
                  @click="askRevoke(token)"
                >
                  <Trash2 class="size-4" />
                  Revoke
                </Button>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </div>
    </Card>

    <ConfirmDeleteDialog
      v-model:open="confirmOpen"
      :title="`Revoke ${pending?.name ?? 'this token'}?`"
      description="An AI client that uses this token loses access with its next request. This cannot be undone."
      confirm-label="Revoke"
      :deleting="tokensStore.revokingId !== null"
      confirm-test-id="confirm-revoke"
      @confirm="confirmRevoke"
    />
  </section>
</template>
