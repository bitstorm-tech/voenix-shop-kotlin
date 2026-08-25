<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ChevronDown, Pencil, Plus, RefreshCw } from 'lucide-vue-next'
import AdminProductionDestinationDialog from '@/components/admin/logistics/AdminProductionDestinationDialog.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { useDialogCrud } from '@/composables/useDialogCrud'
import { useFormErrors } from '@/composables/useFormErrors'
import { useToast } from '@/composables/useToast'
import {
  type AdminProductionDestinationDto,
  DestinationInUseError,
  DestinationNotFoundError,
  InvalidDestinationRequestError,
  type SaveProductionDestinationRequest,
  type TshirtSyncReport,
  useAdminProductionDestinationsStore,
} from '@/stores/admin/productionDestinations'
import { useAdminSuppliersStore } from '@/stores/admin/suppliers'

const destinationsStore = useAdminProductionDestinationsStore()
const suppliersStore = useAdminSuppliersStore()
const { toast } = useToast()
const { generalError, clearErrors } = useFormErrors<never>()

/** Backend messages of the last rejected write, keyed by the JSON path the dialog renders them at. */
const saveFieldErrors = ref<Record<string, string>>({})

function clearDialogErrors() {
  saveFieldErrors.value = {}
  clearErrors()
}

const {
  isOpen: isDialogOpen,
  selected: selectedDestination,
  isLoadingSelected: isLoadingDestination,
  isSaving,
  isDeleting,
  openCreate,
  openEditById,
  save,
  deleteSelected,
} = useDialogCrud<AdminProductionDestinationDto, SaveProductionDestinationRequest>({
  errors: { generalError, clearErrors: clearDialogErrors },
  notFoundError: DestinationNotFoundError,
  messages: {
    notFound: {
      title: 'Destination not found',
      fallbackDescription: 'The requested production destination does not exist.',
    },
    loadFailed: {
      title: 'Failed to load destination',
      fallbackDescription: 'Failed to load the production destination.',
    },
    saveFailed: {
      title: 'Failed to save destination',
      fallbackDescription: 'Failed to save the production destination.',
    },
    deleteFailed: {
      title: 'Failed to delete destination',
      fallbackDescription: 'Failed to delete the production destination.',
    },
  },
  fetchEntity: (id) => destinationsStore.fetchDestination(id),
  createEntity: (payload) => destinationsStore.createDestination(payload),
  updateEntity: (id, payload) => destinationsStore.updateDestination(id, payload),
  deleteEntity: (id) => destinationsStore.deleteDestination(id),
  getId: (destination) => destination.id,
  savedToast: (destination, isEdit) => ({
    title: isEdit ? 'Destination saved' : 'Destination created',
    description: `${destination.label} was saved.`,
  }),
  deletedToast: (destination) => ({
    title: 'Destination deleted',
    description: `${destination.label} was deleted.`,
  }),
  onNotFound: () => destinationsStore.fetchDestinations(),
  handleSaveError: (error) => {
    // Every reference and rule problem of a destination write is a field error on the JSON path of
    // the value that caused it, so the messages go onto the inputs instead of into one alert.
    if (error instanceof InvalidDestinationRequestError) {
      const fieldErrors: Record<string, string> = {}
      for (const [path, messages] of Object.entries(error.fieldErrors)) {
        const message = messages[0]
        if (message !== undefined) {
          fieldErrors[path] = message
        }
      }
      saveFieldErrors.value = fieldErrors
      generalError.value = Object.keys(fieldErrors).length === 0 ? error.message : null
      toast({
        title: 'Failed to save destination',
        description: error.message || 'The production destination was refused.',
        variant: 'destructive',
      })
      return true
    }

    return false
  },
  handleDeleteError: (error) => {
    if (error instanceof DestinationInUseError) {
      generalError.value =
        error.message ||
        'This destination is referenced by production jobs and cannot be deleted. Disable it instead.'
      toast({
        title: 'Destination cannot be deleted',
        description: generalError.value,
        variant: 'destructive',
      })
      return true
    }

    return false
  },
})

const supplierNames = computed(() => {
  const names = new Map<number, string>()
  for (const supplier of suppliersStore.suppliers) {
    names.set(supplier.id, supplier.name)
  }
  return names
})

interface DestinationRow {
  destination: AdminProductionDestinationDto
  supplierName: string
  /** The account the channel talks to, in one line: an SFTP target, or the SPOD installation. */
  accountNote: string
  /** Whether this destination's sync request is still in flight. */
  syncing: boolean
  /** What its last finished sync did, or `null` while nobody has pressed the button. */
  report: TshirtSyncReport | null
}

const rows = computed<DestinationRow[]>(() =>
  destinationsStore.destinations.map((destination) => ({
    destination,
    supplierName:
      supplierNames.value.get(destination.supplierId) ?? `Supplier #${destination.supplierId}`,
    accountNote: accountNote(destination),
    syncing: destinationsStore.isSyncing(destination.id),
    report: destinationsStore.syncReport(destination.id),
  })),
)

function accountNote(destination: AdminProductionDestinationDto): string {
  if (destination.channel === 'SFTP' && destination.sftp) {
    const { username, host, port, remotePath } = destination.sftp
    return `${username}@${host}:${port}${remotePath}`
  }

  if (destination.channel === 'SPOD' && destination.spod) {
    return `${destination.spod.environment} · ${destination.spod.timeoutSeconds}s`
  }

  return '—'
}

function openEditDestination(destination: AdminProductionDestinationDto) {
  void openEditById(destination.id)
}

/** Which rows currently show their warning list. Only the expanded ones are in here. */
const expandedWarnings = ref<Record<number, boolean>>({})

async function syncArticles(destination: AdminProductionDestinationDto) {
  // The previous run's report is dropped by the store; its expanded warning list belongs to that
  // report, so a new run starts collapsed instead of reopening on a different set of warnings.
  delete expandedWarnings.value[destination.id]

  try {
    await destinationsStore.syncArticles(destination.id)
  } catch (error) {
    toast({
      title: 'Sync failed',
      description:
        error instanceof Error && error.message
          ? error.message
          : `The t-shirt catalog of ${destination.label} could not be synced.`,
      variant: 'destructive',
    })
  }
}

onMounted(async () => {
  await Promise.all([destinationsStore.fetchDestinations(), suppliersStore.fetchSuppliers()])
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Production Destinations" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            :disabled="destinationsStore.isLoading"
            @click="destinationsStore.fetchDestinations"
          >
            <RefreshCw :class="['size-4', destinationsStore.isLoading && 'animate-spin']" />
            Reload
          </Button>
          <Button size="sm" @click="openCreate">
            <Plus class="size-4" />
            Add Destination
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <p class="text-sm text-muted-foreground">
      Where a supplier's production jobs go: an SFTP upload, or the print-on-demand partner's API. A
      supplier may have at most one enabled SPOD destination. Credentials are stored write-only and
      are never shown again.
    </p>

    <Alert v-if="destinationsStore.error" variant="destructive">
      Failed to load production destinations. {{ destinationsStore.error }}
    </Alert>

    <Card
      v-else-if="destinationsStore.isLoading && destinationsStore.destinations.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading production destinations...
    </Card>

    <Card
      v-else-if="destinationsStore.destinations.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No production destinations found.
    </Card>

    <Card v-else class="overflow-hidden">
      <div class="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Label</TableHead>
              <TableHead>Supplier</TableHead>
              <TableHead>Channel</TableHead>
              <TableHead>Account</TableHead>
              <TableHead>Notification</TableHead>
              <TableHead>Status</TableHead>
              <TableHead class="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <template
              v-for="{ destination, supplierName, accountNote, syncing, report } in rows"
              :key="destination.id"
            >
              <TableRow :data-testid="`destination-row-${destination.id}`">
                <TableCell class="min-w-40 font-medium text-foreground">
                  {{ destination.label }}
                </TableCell>
                <TableCell class="min-w-32 text-muted-foreground">{{ supplierName }}</TableCell>
                <TableCell class="whitespace-nowrap">
                  <Badge :variant="destination.channel === 'SPOD' ? 'success' : 'muted'">
                    {{ destination.channel }}
                  </Badge>
                </TableCell>
                <TableCell class="min-w-48 text-muted-foreground">{{ accountNote }}</TableCell>
                <TableCell class="min-w-40 text-muted-foreground">
                  {{ destination.notificationEmail ?? '—' }}
                </TableCell>
                <TableCell class="whitespace-nowrap">
                  <Badge :variant="destination.enabled ? 'success' : 'muted'">
                    {{ destination.enabled ? 'Enabled' : 'Disabled' }}
                  </Badge>
                </TableCell>
                <TableCell class="whitespace-nowrap text-right">
                  <div class="flex items-center justify-end gap-2">
                    <!-- A disabled destination still syncs: its catalog is read, its jobs are not
                       sent (issue #224, decision D5). -->
                    <Button
                      v-if="destination.channel === 'SPOD'"
                      type="button"
                      variant="outline"
                      size="sm"
                      :disabled="syncing"
                      :data-testid="`destination-sync-${destination.id}`"
                      @click="syncArticles(destination)"
                    >
                      <RefreshCw :class="['size-4', syncing && 'animate-spin']" />
                      {{ syncing ? 'Syncing...' : 'Sync from Spreadconnect' }}
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      size="icon-sm"
                      :aria-label="`Edit destination ${destination.label}`"
                      @click="openEditDestination(destination)"
                    >
                      <Pencil class="size-4" />
                    </Button>
                  </div>
                </TableCell>
              </TableRow>

              <TableRow v-if="report" :data-testid="`destination-sync-report-${destination.id}`">
                <TableCell colspan="7" class="bg-muted/30">
                  <div class="space-y-2 text-sm">
                    <Alert v-if="report.status === 'FAILED'" variant="destructive">
                      The catalog could not be read to the end, so nothing was written. Reason:
                      {{ report.failure ?? 'UNKNOWN' }}
                    </Alert>

                    <p v-else class="text-muted-foreground">
                      Read {{ report.fetchedArticles }} articles from {{ report.environment }}.
                    </p>

                    <div class="flex flex-wrap gap-x-4 gap-y-1 text-foreground">
                      <span>Created {{ report.created.length }}</span>
                      <span>Updated {{ report.updated.length }}</span>
                      <span>Unchanged {{ report.unchanged.length }}</span>
                      <span>Deactivated {{ report.deactivated.length }}</span>
                      <span>Failed {{ report.failed.length }}</span>
                    </div>

                    <Collapsible
                      v-if="report.warnings.length > 0"
                      v-slot="{ open }"
                      :open="expandedWarnings[destination.id] === true"
                      @update:open="expandedWarnings[destination.id] = $event"
                    >
                      <CollapsibleTrigger
                        type="button"
                        class="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground"
                        :data-testid="`destination-sync-warnings-toggle-${destination.id}`"
                      >
                        <ChevronDown
                          class="size-4 transition-transform"
                          :class="{ 'rotate-180': open }"
                        />
                        {{ open ? 'Hide' : 'Show' }} {{ report.warnings.length }} warnings
                      </CollapsibleTrigger>
                      <CollapsibleContent>
                        <ul
                          class="mt-2 space-y-1 text-muted-foreground"
                          :data-testid="`destination-sync-warnings-${destination.id}`"
                        >
                          <li v-for="(warning, index) in report.warnings" :key="index">
                            <span class="font-medium text-foreground">{{ warning.code }}</span>
                            <span v-if="warning.spodArticleId"> · {{ warning.spodArticleId }}</span>
                            · {{ warning.detail }}
                          </li>
                        </ul>
                      </CollapsibleContent>
                    </Collapsible>
                  </div>
                </TableCell>
              </TableRow>
            </template>
          </TableBody>
        </Table>
      </div>
    </Card>

    <AdminProductionDestinationDialog
      v-model:open="isDialogOpen"
      :destination="selectedDestination"
      :loading="isLoadingDestination"
      :suppliers="suppliersStore.suppliers"
      :suppliers-loading="suppliersStore.isLoading"
      :saving="isSaving"
      :deleting="isDeleting"
      :general-error="generalError"
      :field-errors="saveFieldErrors"
      @save="save"
      @delete="deleteSelected"
      @clear-errors="clearDialogErrors"
    />
  </section>
</template>
