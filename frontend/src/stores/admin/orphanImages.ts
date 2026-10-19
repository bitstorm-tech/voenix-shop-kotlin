import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'

/** Where an original lives: `path` is relative to the public or the private image root. */
export interface ImageLocation {
  visibility: 'public' | 'private'
  path: string
}

/**
 * One orphan as `GET /api/admin/images/orphans` answers it: an original no article, category,
 * prompt, cart, or order refers to, older than seven days (Image package guide).
 */
export interface OrphanImage extends ImageLocation {
  size: number
  modifiedAt: string
}

/** What a delete did: the backend deletes only what is still an orphan and keeps the rest. */
export interface OrphanDeleteResult {
  deleted: ImageLocation[]
  kept: ImageLocation[]
}

const ORPHANS_PATH = '/api/admin/images/orphans'

const sameLocation = (a: ImageLocation, b: ImageLocation) =>
  a.visibility === b.visibility && a.path === b.path

/** The URL of a preview of `image`, `size` pixels wide, through the image delivery routes. */
export const orphanImageUrl = (image: ImageLocation, size: number) =>
  `/api/images/${image.visibility}/${String(size)}/${image.path}`

export const useAdminOrphanImagesStore = defineStore('admin-orphan-images', () => {
  const images = ref<OrphanImage[]>([])
  const isLoading = shallowRef(false)
  /** The failure of the last load. The view decides how to word it. */
  const error = shallowRef<Error | null>(null)

  async function fetchOrphans(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      images.value = await fetchJson<OrphanImage[]>(ORPHANS_PATH)
    } catch (err) {
      images.value = []
      error.value = err instanceof Error ? err : new Error('Unknown error')
    } finally {
      isLoading.value = false
    }
  }

  /** Deletes `locations` and drops every deleted one from the list. */
  async function deleteOrphans(locations: ImageLocation[]): Promise<OrphanDeleteResult> {
    const result = await fetchJson<OrphanDeleteResult>(`${ORPHANS_PATH}/delete`, {
      method: 'POST',
      body: {
        images: locations.map(({ visibility, path }) => ({ visibility, path })),
      },
    })
    images.value = images.value.filter(
      (image) => !result.deleted.some((deleted) => sameLocation(deleted, image)),
    )
    return result
  }

  return { images, isLoading, error, fetchOrphans, deleteOrphans }
})
