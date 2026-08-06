/**
 * Saves a blob the browser already holds under a chosen file name.
 *
 * Downloads that go through the API client arrive as a blob, so the server's `Content-Disposition`
 * name is not what the browser writes to disk — the caller picks the name here. The object URL is
 * revoked right away: the download has already been handed to the browser at that point.
 */
export function saveBlobAs(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
