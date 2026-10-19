<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { RefreshCw, Trash2 } from 'lucide-vue-next'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { formatAdminStamp } from '@/lib/adminStamp'
import {
  orphanImageUrl,
  useAdminOrphanImagesStore,
  type OrphanImage,
} from '@/stores/admin/orphanImages'

const orphansStore = useAdminOrphanImagesStore()

/** What the confirmation dialog deletes: one image, or every listed one. */
const pending = ref<OrphanImage[] | null>(null)
/** The count the dialog title names; kept after a delete so the closing dialog does not say 0. */
const pendingCount = shallowRef(0)
const isDeleting = shallowRef(false)
const deleteError = shallowRef<string | null>(null)
const keptMessage = shallowRef<string | null>(null)

const confirmOpen = computed({
  get: () => pending.value !== null,
  set: (open: boolean) => {
    if (!open && !isDeleting.value) pending.value = null
  },
})

const confirmTitle = computed(() =>
  pendingCount.value === 1
    ? 'Delete this image?'
    : `Delete all ${String(pendingCount.value)} images?`,
)

const listError = computed(() =>
  orphansStore.error === null
    ? null
    : orphansStore.error.message || 'The orphaned images could not be loaded.',
)

/**
 * The two tables: customer images (print images a customer uploaded or generated, below the private
 * root) and everything else (article, category, and prompt images below the public root).
 */
const sections = computed(() =>
  (
    [
      { key: 'customer', title: 'Customer images', visibility: 'private' },
      { key: 'other', title: 'Other images', visibility: 'public' },
    ] as const
  ).map((section) => {
    const images = orphansStore.images.filter((image) => image.visibility === section.visibility)
    return {
      ...section,
      images,
      totalSize: formatBytes(images.reduce((sum, image) => sum + image.size, 0)),
    }
  }),
)

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${String(bytes)} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** The folder an image lives in, the part a person recognizes ("articles/spod/mockups"). */
function folderOf(image: OrphanImage): string {
  const slash = image.path.lastIndexOf('/')
  return slash === -1 ? '(root)' : image.path.slice(0, slash)
}

function filenameOf(image: OrphanImage): string {
  return image.path.slice(image.path.lastIndexOf('/') + 1)
}

function reload() {
  keptMessage.value = null
  deleteError.value = null
  void orphansStore.fetchOrphans()
}

function askDelete(images: OrphanImage[]) {
  deleteError.value = null
  keptMessage.value = null
  pending.value = images
  pendingCount.value = images.length
}

async function confirmDelete() {
  if (pending.value === null) return
  isDeleting.value = true
  try {
    const result = await orphansStore.deleteOrphans(pending.value)
    if (result.kept.length > 0) {
      keptMessage.value = `${String(result.kept.length)} image(s) were kept: they are in use again or no longer exist. The list has been reloaded.`
      await orphansStore.fetchOrphans()
    }
    pending.value = null
  } catch (err) {
    deleteError.value = err instanceof Error && err.message ? err.message : 'The delete failed.'
    pending.value = null
  } finally {
    isDeleting.value = false
  }
}

onMounted(reload)
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Orphaned images" breakpoint="lg">
      <template #actions>
        <Button variant="outline" size="sm" :disabled="orphansStore.isLoading" @click="reload">
          <RefreshCw :class="['size-4', orphansStore.isLoading && 'animate-spin']" />
          Reload
        </Button>
      </template>
    </AdminPageHeader>

    <p class="text-sm text-muted-foreground">
      Stored images that no article, category, prompt, cart, or order uses any more and that are
      older than seven days. Deleting one removes the file for good; an image that is in use again
      by then is kept.
    </p>

    <Alert v-if="deleteError" variant="destructive">{{ deleteError }}</Alert>
    <Alert v-if="keptMessage" variant="warning">{{ keptMessage }}</Alert>
    <Alert v-if="listError" variant="destructive">{{ listError }}</Alert>

    <Card
      v-else-if="orphansStore.isLoading && orphansStore.images.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading images...
    </Card>

    <Card
      v-else-if="orphansStore.images.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      There is no orphaned image.
    </Card>

    <template v-else>
      <section
        v-for="section in sections"
        :key="section.key"
        class="space-y-2"
        :data-testid="`orphan-section-${section.key}`"
      >
        <div class="flex flex-wrap items-end justify-between gap-2">
          <div>
            <h2 class="text-base font-semibold">{{ section.title }}</h2>
            <p class="text-sm text-muted-foreground" data-testid="orphan-summary">
              {{ section.images.length }} image(s), {{ section.totalSize }} in total.
            </p>
          </div>
          <Button
            variant="destructive"
            size="sm"
            :disabled="section.images.length === 0"
            :data-testid="`delete-all-${section.key}`"
            @click="askDelete([...section.images])"
          >
            <Trash2 class="size-4" />
            Delete all
          </Button>
        </div>

        <Card class="overflow-hidden">
          <div class="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead class="w-20">Preview</TableHead>
                  <TableHead>File</TableHead>
                  <TableHead v-if="section.key === 'other'">Folder</TableHead>
                  <TableHead class="text-right">Size</TableHead>
                  <TableHead>Modified</TableHead>
                  <TableHead class="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                <TableRow v-if="section.images.length === 0">
                  <TableCell
                    :colspan="section.key === 'other' ? 6 : 5"
                    class="py-6 text-center text-muted-foreground"
                  >
                    There is no orphaned image here.
                  </TableCell>
                </TableRow>
                <TableRow
                  v-for="image in section.images"
                  :key="image.path"
                  data-testid="orphan-image"
                >
                  <TableCell>
                    <a
                      :href="orphanImageUrl(image, 1200)"
                      target="_blank"
                      rel="noopener"
                      class="block size-14 overflow-hidden rounded border bg-muted/30"
                    >
                      <img
                        :src="orphanImageUrl(image, 400)"
                        :alt="filenameOf(image)"
                        loading="lazy"
                        class="size-full object-contain"
                      />
                    </a>
                  </TableCell>
                  <TableCell class="max-w-64 truncate font-medium" :title="image.path">
                    {{ filenameOf(image) }}
                  </TableCell>
                  <TableCell v-if="section.key === 'other'" class="text-muted-foreground">
                    {{ folderOf(image) }}
                  </TableCell>
                  <TableCell class="text-right whitespace-nowrap text-muted-foreground">
                    {{ formatBytes(image.size) }}
                  </TableCell>
                  <TableCell class="whitespace-nowrap text-muted-foreground">
                    {{ formatAdminStamp(image.modifiedAt) }}
                  </TableCell>
                  <TableCell class="text-right">
                    <Button
                      variant="outline"
                      size="sm"
                      :data-testid="`delete-${image.path}`"
                      @click="askDelete([image])"
                    >
                      <Trash2 class="size-4" />
                      Delete
                    </Button>
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </div>
        </Card>
      </section>
    </template>

    <ConfirmDeleteDialog
      v-model:open="confirmOpen"
      :title="confirmTitle"
      description="The files are deleted permanently. Images that are in use again by now are kept."
      :deleting="isDeleting"
      confirm-test-id="confirm-delete"
      @confirm="confirmDelete"
    />
  </section>
</template>
