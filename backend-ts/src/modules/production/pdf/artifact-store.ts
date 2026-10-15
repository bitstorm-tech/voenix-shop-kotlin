import { randomBytes } from 'node:crypto'
import { mkdir, readFile, realpath, rename, rm, writeFile } from 'node:fs/promises'
import { basename, join, resolve } from 'node:path'
import { sha256Hex } from './renderer'

/** Loading an artifact: the verified bytes, no file, or a file that is not the generated one. */
export type ArtifactLoadResult =
  | { kind: 'loaded'; bytes: Uint8Array<ArrayBuffer> }
  | { kind: 'missing' }
  | { kind: 'digestMismatch'; actualSha256: string }

/**
 * The immutable production PDFs under a production-owned private root, at
 * `{root}/{jobId}/{fileName}`: the job id keeps two suppliers' `ORD-{orderId}.pdf` apart. A write
 * goes to a temporary file in the final directory and is renamed atomically, so a half-written PDF
 * is never visible under the final path, and repeating a write after a crash replaces the file.
 * `load` recomputes the digest, so nobody ever serves or delivers bytes that are not the generated
 * ones. The root is created here, so an unusable root fails the startup.
 */
export async function createArtifactStore(root: string) {
  await mkdir(root, { recursive: true })
  const realRoot = await realpath(resolve(root))

  const pathOf = (jobId: number, fileName: string) => {
    if (!Number.isSafeInteger(jobId) || jobId <= 0) {
      throw new Error('Production artifacts require a positive job id')
    }
    if (['', '.', '..'].includes(fileName) || basename(fileName) !== fileName) {
      throw new Error('Production artifact file name must be a plain file name')
    }
    const directory = join(realRoot, String(jobId))
    return { directory, target: join(directory, fileName) }
  }

  return {
    write: async (jobId: number, fileName: string, bytes: Uint8Array): Promise<void> => {
      const { directory, target } = pathOf(jobId, fileName)
      await mkdir(directory, { recursive: true })
      const temporary = join(directory, `${fileName}.${randomBytes(6).toString('hex')}.tmp`)
      try {
        await writeFile(temporary, bytes)
        await rename(temporary, target)
      } finally {
        await rm(temporary, { force: true })
      }
    },

    load: async (
      jobId: number,
      fileName: string,
      expectedSha256: string,
    ): Promise<ArtifactLoadResult> => {
      const { target } = pathOf(jobId, fileName)
      let bytes: Uint8Array<ArrayBuffer>
      try {
        bytes = await readFile(target)
      } catch (error) {
        if (isMissing(error)) return { kind: 'missing' }
        throw error
      }
      const actualSha256 = sha256Hex(bytes)
      return actualSha256 === expectedSha256.toLowerCase()
        ? { kind: 'loaded', bytes }
        : { kind: 'digestMismatch', actualSha256 }
    },
  }
}

export type ArtifactStore = Awaited<ReturnType<typeof createArtifactStore>>

const isMissing = (error: unknown) =>
  typeof error === 'object' &&
  error !== null &&
  'code' in error &&
  (error.code === 'ENOENT' || error.code === 'EISDIR')
