<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import OperationsFindingsTable from '@/components/admin/operations/OperationsFindingsTable.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
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

function reload() {
  void operationsStore.fetchFindings(resolved.value)
}

watch(resolved, reload)
onMounted(reload)

function selectList(value: string) {
  list.value = value === 'RESOLVED' ? 'RESOLVED' : 'OPEN'
}
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Operations" breakpoint="lg">
      <template #actions>
        <Button variant="outline" size="sm" :disabled="operationsStore.isLoading" @click="reload">
          <RefreshCw :class="['size-4', operationsStore.isLoading && 'animate-spin']" />
          Reload
        </Button>
      </template>
    </AdminPageHeader>

    <p class="text-sm text-muted-foreground">
      Orders that stand still: payments, production, delivery, shipping, and mails. The list is
      refreshed by a scan every five minutes; a finding resolves on its own once its row moves on.
    </p>

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
