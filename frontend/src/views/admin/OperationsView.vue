<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import OperationsFindingsTable from '@/components/admin/operations/OperationsFindingsTable.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { formatAdminStamp } from '@/lib/adminStamp'
import { useAdminOperationsStore } from '@/stores/admin/operations'

const operationsStore = useAdminOperationsStore()

/** Which list is shown: the open findings, or the ones resolved within the last seven days. */
const list = ref<'OPEN' | 'RESOLVED'>('OPEN')
const resolved = computed(() => list.value === 'RESOLVED')

const listError = computed(() =>
  operationsStore.error === null
    ? null
    : operationsStore.error.message || 'The findings could not be loaded.',
)

const emptyMessage = computed(() =>
  resolved.value
    ? 'No finding was resolved in the last seven days.'
    : 'Nothing is stuck: there is no open finding.',
)

/**
 * The worker scans every five minutes; a last scan older than three intervals means scans are
 * failing or the worker is not running.
 */
const STALE_SCAN_MINUTES = 15

/** How many whole minutes ago the last scan was, or `null` when it is unknown or never happened. */
const lastScanMinutesAgo = computed(() => {
  const at = operationsStore.lastScanAt
  if (at === null || at === undefined) return null
  const millis = Date.now() - new Date(at).getTime()
  return Number.isNaN(millis) ? null : Math.max(0, Math.floor(millis / 60_000))
})

const lastScanText = computed(() => {
  const at = operationsStore.lastScanAt
  if (at === undefined) return 'Last scan: unknown, the time could not be loaded.'
  if (at === null) return 'Last scan: none yet.'
  const minutes = lastScanMinutesAgo.value
  const ago =
    minutes === null ? '' : minutes === 0 ? ' (just now)' : ` (${String(minutes)} min ago)`
  return `Last scan: ${formatAdminStamp(at) ?? at}${ago}`
})

const lastScanIsStale = computed(
  () =>
    operationsStore.lastScanAt === null ||
    (lastScanMinutesAgo.value !== null && lastScanMinutesAgo.value >= STALE_SCAN_MINUTES),
)

function reload() {
  void operationsStore.fetchFindings(resolved.value)
}

function reloadAll() {
  reload()
  void operationsStore.fetchLastScan()
}

watch(resolved, reload)
onMounted(reloadAll)

function selectList(value: string) {
  list.value = value === 'RESOLVED' ? 'RESOLVED' : 'OPEN'
}
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Operations" breakpoint="lg">
      <template #actions>
        <Button
          variant="outline"
          size="sm"
          :disabled="operationsStore.isLoading"
          @click="reloadAll"
        >
          <RefreshCw :class="['size-4', operationsStore.isLoading && 'animate-spin']" />
          Reload
        </Button>
      </template>
    </AdminPageHeader>

    <p class="text-sm text-muted-foreground">
      Orders that stand still: payments, production, delivery, shipping, and mails. The list is
      refreshed by a scan every five minutes; a finding resolves on its own once its row moves on.
    </p>

    <Alert v-if="lastScanIsStale" variant="warning" data-testid="last-scan">
      {{ lastScanText }} The operations worker should scan every five minutes; check the backend log
      for "Operations worker scan failed".
    </Alert>
    <p v-else class="text-sm text-muted-foreground" data-testid="last-scan">{{ lastScanText }}</p>

    <Tabs :model-value="list" @update:model-value="selectList($event as string)">
      <TabsList>
        <TabsTrigger value="OPEN">Open</TabsTrigger>
        <TabsTrigger value="RESOLVED">Resolved in the last 7 days</TabsTrigger>
      </TabsList>
    </Tabs>

    <Alert v-if="listError" variant="destructive">{{ listError }}</Alert>

    <Card
      v-else-if="operationsStore.isLoading && operationsStore.findings.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading findings...
    </Card>

    <Card
      v-else-if="operationsStore.findings.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ emptyMessage }}
    </Card>

    <OperationsFindingsTable v-else :findings="operationsStore.findings" :resolved="resolved" />
  </section>
</template>
