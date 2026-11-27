<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { Ban, Check, Coins, RefreshCw, ShieldOff, X } from 'lucide-vue-next'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useToast } from '@/composables/useToast'
import { formatAdminStamp } from '@/lib/adminStamp'
import { ApiError } from '@/lib/api'
import { useAdminMagicCoinsStore, type AdminMagicCoinRequest } from '@/stores/admin/magicCoins'

const DEFAULT_GRANT_AMOUNT = 10

const magicCoinsStore = useAdminMagicCoinsStore()
const { toast } = useToast()

type ActiveTab = 'PENDING' | 'DECIDED' | 'BLOCKED'
const activeTab = shallowRef<ActiveTab>('PENDING')

// Manual grant form state
const grantEmail = shallowRef('')
const grantAmount = shallowRef<string | number>(DEFAULT_GRANT_AMOUNT)
const grantEmailError = shallowRef<string | null>(null)
const grantAmountError = shallowRef<string | null>(null)
const grantFormError = shallowRef<string | null>(null)

// Approve dialog state
const approveTarget = ref<AdminMagicCoinRequest | null>(null)
const approveAmount = shallowRef<string | number>(DEFAULT_GRANT_AMOUNT)
const approveAmountError = shallowRef<string | null>(null)
const approveError = shallowRef<string | null>(null)

// Reject dialog state
const rejectTarget = ref<AdminMagicCoinRequest | null>(null)
const rejectError = shallowRef<string | null>(null)

// Action error on unblock
const unblockError = shallowRef<string | null>(null)

const approveDialogOpen = computed({
  get: () => approveTarget.value !== null,
  set: (open: boolean) => {
    if (!open && !magicCoinsStore.isSubmittingDecision) {
      approveTarget.value = null
      approveAmountError.value = null
      approveError.value = null
    }
  },
})

const rejectDialogOpen = computed({
  get: () => rejectTarget.value !== null,
  set: (open: boolean) => {
    if (!open && !magicCoinsStore.isSubmittingDecision) {
      rejectTarget.value = null
      rejectError.value = null
    }
  },
})

const isLoadingActiveTab = computed(() =>
  activeTab.value === 'BLOCKED'
    ? magicCoinsStore.isLoadingBlocks
    : magicCoinsStore.isLoadingRequests,
)

const activeListError = computed(() => {
  const err =
    activeTab.value === 'BLOCKED' ? magicCoinsStore.blocksError : magicCoinsStore.requestsError
  if (err === null) return null
  return err.message || 'Data could not be loaded.'
})

function parsePositiveInteger(value: string | number): number | null {
  const trimmed = String(value).trim()
  if (!/^\d+$/.test(trimmed)) return null
  const parsed = Number(trimmed)
  return Number.isSafeInteger(parsed) && parsed >= 1 && parsed <= 1000 ? parsed : null
}

function formatConsumptionSpan(startIso: string | null, endIso: string | null): string {
  if (!startIso || !endIso) return '—'
  const start = new Date(startIso).getTime()
  const end = new Date(endIso).getTime()
  if (Number.isNaN(start) || Number.isNaN(end)) return '—'
  const seconds = Math.max(0, Math.round((end - start) / 1000))
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.round(minutes / 60)
  if (hours < 48) return `${hours} h`
  const days = Math.round(hours / 24)
  return `${days} d`
}

function loadActiveTab() {
  unblockError.value = null
  if (activeTab.value === 'BLOCKED') {
    void magicCoinsStore.fetchBlocks()
  } else {
    void magicCoinsStore.fetchRequests(activeTab.value)
  }
}

function selectTab(value: string) {
  if (value === 'DECIDED' || value === 'BLOCKED') {
    activeTab.value = value
  } else {
    activeTab.value = 'PENDING'
  }
}

watch(activeTab, loadActiveTab)
onMounted(loadActiveTab)

function openApprove(request: AdminMagicCoinRequest) {
  approveTarget.value = request
  approveAmount.value = String(DEFAULT_GRANT_AMOUNT)
  approveAmountError.value = null
  approveError.value = null
}

async function confirmApprove() {
  if (approveTarget.value === null) return
  approveAmountError.value = null
  approveError.value = null

  const amount = parsePositiveInteger(approveAmount.value)
  if (amount === null) {
    approveAmountError.value = 'Enter a whole number between 1 and 1000.'
    return
  }

  const target = approveTarget.value
  try {
    const result = await magicCoinsStore.approveRequest(target.id, amount)
    approveTarget.value = null
    toast({
      title: 'Magic Coins granted',
      description: `Added ${result.grantedCoins} Magic Coins to ${target.userEmail} (new balance: ${result.newBalance}).`,
      variant: 'success',
    })
  } catch (err) {
    if (err instanceof ApiError && err.fieldErrors.amount?.[0]) {
      approveAmountError.value = err.fieldErrors.amount[0]
      return
    }
    approveError.value =
      err instanceof Error && err.message ? err.message : 'The request could not be approved.'
  }
}

function openReject(request: AdminMagicCoinRequest) {
  rejectTarget.value = request
  rejectError.value = null
}

async function confirmReject(blockUser: boolean) {
  if (rejectTarget.value === null) return
  rejectError.value = null

  const target = rejectTarget.value
  try {
    await magicCoinsStore.rejectRequest(target.id, blockUser)
    rejectTarget.value = null
    toast({
      title: blockUser ? 'Request rejected and account blocked' : 'Request rejected',
      description: blockUser
        ? `${target.userEmail} can no longer submit Magic Coin requests.`
        : `${target.userEmail} was rejected without blocking future requests.`,
      variant: 'success',
    })
  } catch (err) {
    rejectError.value =
      err instanceof Error && err.message ? err.message : 'The request could not be rejected.'
  }
}

async function submitManualGrant() {
  grantEmailError.value = null
  grantAmountError.value = null
  grantFormError.value = null

  const email = grantEmail.value.trim()
  if (!email) {
    grantEmailError.value = 'Email is required.'
    return
  }

  const amount = parsePositiveInteger(grantAmount.value)
  if (amount === null) {
    grantAmountError.value = 'Enter a whole number between 1 and 1000.'
    return
  }

  try {
    const result = await magicCoinsStore.grantManually(email, amount)
    grantEmail.value = ''
    grantAmount.value = String(DEFAULT_GRANT_AMOUNT)
    toast({
      title: 'Magic Coins granted',
      description: `Added ${result.grantedCoins} Magic Coins to ${result.userEmail} (new balance: ${result.newBalance}).`,
      variant: 'success',
    })
    loadActiveTab()
  } catch (err) {
    if (err instanceof ApiError) {
      if (err.fieldErrors.email?.[0]) {
        grantEmailError.value = err.fieldErrors.email[0]
        return
      }
      if (err.fieldErrors.amount?.[0]) {
        grantAmountError.value = err.fieldErrors.amount[0]
        return
      }
    }
    grantFormError.value =
      err instanceof Error && err.message ? err.message : 'Magic Coins could not be granted.'
  }
}

async function handleUnblock(userId: number, email: string) {
  unblockError.value = null
  try {
    await magicCoinsStore.unblockUser(userId)
    toast({
      title: 'Account unblocked',
      description: `${email} can submit Magic Coin requests again.`,
      variant: 'success',
    })
  } catch (err) {
    unblockError.value =
      err instanceof Error && err.message ? err.message : 'The account could not be unblocked.'
  }
}
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Magic Coins" breakpoint="lg">
      <template #actions>
        <Button variant="outline" size="sm" :disabled="isLoadingActiveTab" @click="loadActiveTab">
          <RefreshCw :class="['size-4', isLoadingActiveTab && 'animate-spin']" />
          Reload
        </Button>
      </template>
    </AdminPageHeader>

    <p class="text-sm text-muted-foreground">
      Review refill requests from signed-in customers who ran out of Magic Coins. Approving a
      request or granting coins manually sends the customer a notification email without naming the
      exact coin count; rejecting sends no email.
    </p>

    <Card class="p-4">
      <form class="flex flex-wrap items-start gap-3" @submit.prevent="submitManualGrant">
        <FormField
          label="Grant coins directly (customer email)"
          for="manual-grant-email"
          :error="grantEmailError"
          class="min-w-64 flex-1"
        >
          <Input
            id="manual-grant-email"
            v-model="grantEmail"
            type="email"
            placeholder="customer@example.com"
            :aria-invalid="grantEmailError ? true : undefined"
          />
        </FormField>
        <FormField label="Coins" for="manual-grant-amount" :error="grantAmountError" class="w-32">
          <Input
            id="manual-grant-amount"
            v-model="grantAmount"
            type="number"
            min="1"
            max="1000"
            :aria-invalid="grantAmountError ? true : undefined"
          />
        </FormField>
        <Button
          type="submit"
          class="mt-6"
          :disabled="magicCoinsStore.isGranting"
          data-testid="manual-grant-submit"
        >
          <Coins class="size-4" />
          Grant Magic Coins
        </Button>
      </form>
    </Card>

    <Alert v-if="grantFormError" variant="destructive" data-testid="manual-grant-error">
      {{ grantFormError }}
    </Alert>

    <Tabs :model-value="activeTab" @update:model-value="selectTab($event as string)">
      <TabsList>
        <TabsTrigger value="PENDING">Pending</TabsTrigger>
        <TabsTrigger value="DECIDED">History</TabsTrigger>
        <TabsTrigger value="BLOCKED">Blocked accounts</TabsTrigger>
      </TabsList>
    </Tabs>

    <Alert v-if="unblockError" variant="destructive">{{ unblockError }}</Alert>
    <Alert v-if="activeListError" variant="destructive">{{ activeListError }}</Alert>

    <Card
      v-else-if="
        isLoadingActiveTab &&
        ((activeTab === 'BLOCKED' && magicCoinsStore.blocks.length === 0) ||
          (activeTab !== 'BLOCKED' && magicCoinsStore.requests.length === 0))
      "
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading...
    </Card>

    <!-- Blocked accounts table -->
    <template v-else-if="activeTab === 'BLOCKED'">
      <Card
        v-if="magicCoinsStore.blocks.length === 0"
        class="px-4 py-12 text-center text-sm text-muted-foreground"
      >
        No accounts are currently blocked from requesting Magic Coins.
      </Card>

      <Card v-else class="overflow-hidden">
        <div class="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Account</TableHead>
                <TableHead>Paid orders</TableHead>
                <TableHead>Balance</TableHead>
                <TableHead>Blocked</TableHead>
                <TableHead class="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow
                v-for="block in magicCoinsStore.blocks"
                :key="block.userId"
                data-testid="blocked-account-row"
              >
                <TableCell>
                  <div class="font-medium">{{ block.userEmail }}</div>
                  <div v-if="block.userName" class="text-xs text-muted-foreground">
                    {{ block.userName }}
                  </div>
                  <div v-if="block.accountCreatedAt" class="text-xs text-muted-foreground">
                    Joined {{ formatAdminStamp(block.accountCreatedAt) }}
                  </div>
                </TableCell>
                <TableCell class="tabular-nums">{{ block.paidOrderCount }}</TableCell>
                <TableCell class="tabular-nums">{{ block.currentBalance }}</TableCell>
                <TableCell class="text-sm text-muted-foreground">
                  <div>{{ formatAdminStamp(block.blockedAt) }}</div>
                  <div v-if="block.blockedByEmail" class="text-xs">
                    by {{ block.blockedByEmail }}
                  </div>
                </TableCell>
                <TableCell class="text-right">
                  <Button
                    variant="outline"
                    size="sm"
                    :disabled="magicCoinsStore.unblockingUserId === block.userId"
                    :data-testid="`unblock-${block.userId}`"
                    @click="handleUnblock(block.userId, block.userEmail)"
                  >
                    <ShieldOff class="size-4" />
                    Unblock
                  </Button>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>
      </Card>
    </template>

    <!-- Pending & History requests table -->
    <template v-else>
      <Card
        v-if="magicCoinsStore.requests.length === 0"
        class="px-4 py-12 text-center text-sm text-muted-foreground"
      >
        {{
          activeTab === 'PENDING'
            ? 'There are no pending Magic Coin requests.'
            : 'No Magic Coin requests have been decided yet.'
        }}
      </Card>

      <Card v-else class="overflow-hidden">
        <div class="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Account</TableHead>
                <TableHead>Shop activity</TableHead>
                <TableHead>Coin usage</TableHead>
                <TableHead>Requested</TableHead>
                <TableHead v-if="activeTab === 'DECIDED'">Decision</TableHead>
                <TableHead v-if="activeTab === 'PENDING'" class="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow
                v-for="request in magicCoinsStore.requests"
                :key="request.id"
                data-testid="magic-coin-request-row"
              >
                <TableCell>
                  <div class="font-medium">{{ request.userEmail }}</div>
                  <div v-if="request.userName" class="text-xs text-muted-foreground">
                    {{ request.userName }}
                  </div>
                  <div v-if="request.accountCreatedAt" class="text-xs text-muted-foreground">
                    Joined {{ formatAdminStamp(request.accountCreatedAt) }}
                  </div>
                </TableCell>
                <TableCell class="text-sm">
                  <div class="flex flex-wrap gap-1.5">
                    <Badge :variant="request.paidOrderCount > 0 ? 'success' : 'muted'">
                      {{ request.paidOrderCount }} paid
                      {{ request.paidOrderCount === 1 ? 'order' : 'orders' }}
                    </Badge>
                    <Badge :variant="request.activeCartItemCount > 0 ? 'warning' : 'muted'">
                      {{ request.activeCartItemCount }} in cart
                    </Badge>
                  </div>
                </TableCell>
                <TableCell class="text-sm text-muted-foreground">
                  <div class="tabular-nums text-foreground">
                    Balance: {{ request.currentBalance }}
                  </div>
                  <div class="text-xs">
                    {{ request.totalRequestCount }}
                    {{ request.totalRequestCount === 1 ? 'request' : 'requests' }} ·
                    {{ request.totalApprovedCoins }} coins granted
                  </div>
                  <div class="text-xs">
                    Spend span:
                    {{
                      formatConsumptionSpan(request.coinsFirstSpentAt, request.coinsLastUpdatedAt)
                    }}
                  </div>
                </TableCell>
                <TableCell class="whitespace-nowrap text-sm text-muted-foreground">
                  {{ formatAdminStamp(request.createdAt) }}
                </TableCell>
                <TableCell v-if="activeTab === 'DECIDED'" class="text-sm">
                  <div class="flex flex-wrap items-center gap-1.5">
                    <Badge v-if="request.status === 'APPROVED'" variant="success">
                      Approved (+{{ request.grantedCoins }})
                    </Badge>
                    <Badge v-else-if="request.blockedUser" variant="warning">
                      Rejected &amp; blocked
                    </Badge>
                    <Badge v-else variant="muted">Rejected</Badge>
                  </div>
                  <div v-if="request.decidedAt" class="mt-1 text-xs text-muted-foreground">
                    {{ formatAdminStamp(request.decidedAt) }}
                    <span v-if="request.decidedByEmail">· {{ request.decidedByEmail }}</span>
                  </div>
                </TableCell>
                <TableCell v-if="activeTab === 'PENDING'" class="text-right">
                  <div class="flex justify-end gap-2">
                    <Button
                      size="sm"
                      :data-testid="`approve-${request.id}`"
                      @click="openApprove(request)"
                    >
                      <Check class="size-4" />
                      Approve
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      :data-testid="`reject-${request.id}`"
                      @click="openReject(request)"
                    >
                      <X class="size-4" />
                      Reject
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>
      </Card>
    </template>

    <!-- Approve dialog -->
    <Dialog v-model:open="approveDialogOpen">
      <DialogContent class="sm:max-w-md" data-testid="approve-request-dialog">
        <DialogHeader>
          <DialogTitle>Approve Magic Coins request</DialogTitle>
          <DialogDescription>
            Credit new Magic Coins to {{ approveTarget?.userEmail }}. The customer will receive a
            notification email without the exact coin count.
          </DialogDescription>
        </DialogHeader>

        <form class="space-y-4" @submit.prevent="confirmApprove">
          <FormField
            label="Magic Coins to grant"
            for="approve-coin-amount"
            :error="approveAmountError"
          >
            <Input
              id="approve-coin-amount"
              v-model="approveAmount"
              type="number"
              min="1"
              max="1000"
              :aria-invalid="approveAmountError ? true : undefined"
            />
          </FormField>

          <Alert v-if="approveError" variant="destructive">{{ approveError }}</Alert>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              :disabled="magicCoinsStore.isSubmittingDecision"
              @click="approveDialogOpen = false"
            >
              Cancel
            </Button>
            <Button
              type="submit"
              :disabled="magicCoinsStore.isSubmittingDecision"
              data-testid="confirm-approve-button"
            >
              <Check class="size-4" />
              Grant coins
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <!-- Reject dialog -->
    <Dialog v-model:open="rejectDialogOpen">
      <DialogContent class="sm:max-w-md" data-testid="reject-request-dialog">
        <DialogHeader>
          <DialogTitle>Reject Magic Coins request</DialogTitle>
          <DialogDescription>
            Decide how to reject the request from {{ rejectTarget?.userEmail }}. No email is sent to
            the customer.
          </DialogDescription>
        </DialogHeader>

        <Alert v-if="rejectError" variant="destructive">{{ rejectError }}</Alert>

        <DialogFooter class="flex-col gap-2 sm:flex-row sm:justify-end">
          <Button
            type="button"
            variant="outline"
            :disabled="magicCoinsStore.isSubmittingDecision"
            @click="rejectDialogOpen = false"
          >
            Cancel
          </Button>
          <Button
            type="button"
            variant="secondary"
            :disabled="magicCoinsStore.isSubmittingDecision"
            data-testid="confirm-soft-reject-button"
            @click="confirmReject(false)"
          >
            <X class="size-4" />
            Reject
          </Button>
          <Button
            type="button"
            variant="destructive"
            :disabled="magicCoinsStore.isSubmittingDecision"
            data-testid="confirm-block-reject-button"
            @click="confirmReject(true)"
          >
            <Ban class="size-4" />
            Reject &amp; block
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </section>
</template>
