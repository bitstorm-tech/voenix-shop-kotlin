import { fetchForm } from '@/lib/api'

/** Sends `image` to the admin's background removal and answers the cut-out; nothing is stored. */
export async function removeImageBackground(image: Blob): Promise<Blob> {
  const form = new FormData()
  const extension = { 'image/png': 'png', 'image/webp': 'webp' }[image.type] ?? 'jpg'
  form.append('file', image, `image.${extension}`)
  return fetchForm<Blob>('/api/admin/images/background-removal', form, { responseType: 'blob' })
}
