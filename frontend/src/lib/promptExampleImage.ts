export function promptExampleImageUrl(filename: string, size: number): string {
  return `/api/images/public/${size}/prompt-example-images/${filename}`
}
