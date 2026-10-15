import { useEventListener } from '@vueuse/core'

const PASTED_IMAGE_EXTENSIONS: Record<string, string> = {
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
  'image/gif': '.gif',
}

/**
 * Hands an image pasted anywhere on the page (Ctrl+V / Cmd+V) to `onImage`, as long as `enabled`
 * says the caller is ready for one. Clipboard images often carry a generic or missing name, so a
 * known image type is renamed to `pasted-image.<ext>`; the caller still validates it like a chosen
 * file. A paste without an image, such as text in a form field, is left to the browser.
 */
export function useClipboardImagePaste(onImage: (file: File) => void, enabled: () => boolean) {
  useEventListener(document, 'paste', (event: ClipboardEvent) => {
    if (!enabled()) {
      return
    }

    const pastedFile = Array.from(event.clipboardData?.files ?? []).find((file) =>
      file.type.startsWith('image/'),
    )
    if (!pastedFile) {
      return
    }

    event.preventDefault()
    const extension = PASTED_IMAGE_EXTENSIONS[pastedFile.type]
    onImage(
      extension
        ? new File([pastedFile], `pasted-image${extension}`, { type: pastedFile.type })
        : pastedFile,
    )
  })
}
