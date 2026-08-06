import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminArticleMugVariantDialog from '../AdminArticleMugVariantDialog.vue'
import type { MugVariantFormValue } from '../mugVariantForm'

const mocks = vi.hoisted(() => {
  class InvalidArticleRequestError extends Error {
    readonly fieldErrors: Record<string, string[]>

    constructor(message: string, fieldErrors: Record<string, string[]> = {}) {
      super(message)
      this.name = 'InvalidArticleRequestError'
      this.fieldErrors = fieldErrors
    }

    fieldError(field: string): string | null {
      return this.fieldErrors[field]?.[0] ?? null
    }
  }

  return {
    uploadVariantExampleImage: vi.fn(),
    InvalidArticleRequestError,
  }
})

vi.mock('@/stores/admin/articles', () => ({
  useAdminArticlesStore: () => ({
    uploadVariantExampleImage: mocks.uploadVariantExampleImage,
  }),
  InvalidArticleRequestError: mocks.InvalidArticleRequestError,
}))

const baseVariant: MugVariantFormValue = {
  name: 'White',
  insideColorCode: '#ffffff',
  outsideColorCode: '#ffffff',
  isDefault: true,
  active: true,
  exampleImageFilename: null,
}

async function mountDialog(variant: MugVariantFormValue | null = null) {
  const wrapper = mount(AdminArticleMugVariantDialog, {
    attachTo: document.body,
    props: {
      open: true,
      variant,
      isOnlyVariant: true,
    },
  })
  await flushPromises()
  return wrapper
}

function getFileInput() {
  const input = document.querySelector(
    '[data-testid="variant-example-image-input"]',
  ) as HTMLInputElement | null
  expect(input).not.toBeNull()
  return input!
}

async function selectFile(file: File) {
  const input = getFileInput()
  Object.defineProperty(input, 'files', { value: [file], configurable: true })
  input.dispatchEvent(new Event('change'))
  await flushPromises()
}

async function setColorInput(label: string, value: string) {
  const input = document.querySelector(
    `input[type="color"][aria-label="${label}"]`,
  ) as HTMLInputElement | null
  expect(input).not.toBeNull()
  input!.value = value
  input!.dispatchEvent(new Event('input', { bubbles: true }))
  input!.dispatchEvent(new Event('change', { bubbles: true }))
  await flushPromises()
}

function getPreviewSrc() {
  const preview = document.querySelector(
    '[data-testid="variant-example-image-preview"]',
  ) as HTMLImageElement | null
  return preview?.getAttribute('src') ?? null
}

async function submitForm(wrapper: Awaited<ReturnType<typeof mountDialog>>) {
  const form = document.querySelector('form')
  expect(form).not.toBeNull()
  form!.dispatchEvent(new Event('submit', { cancelable: true }))
  await flushPromises()
  return wrapper.emitted('save')
}

function savedPayload(saveEvents: unknown[][] | undefined): MugVariantFormValue {
  const payload = saveEvents?.[0]?.[0]
  expect(payload).toBeDefined()
  return payload as MugVariantFormValue
}

describe('AdminArticleMugVariantDialog', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.uploadVariantExampleImage.mockReset()
  })

  it('uploads a selected image and stages the returned filename', async () => {
    mocks.uploadVariantExampleImage.mockResolvedValue('11111111-2222-3333-4444-555555555555.png')

    const wrapper = await mountDialog(baseVariant)
    await selectFile(new File(['image'], 'mug.png', { type: 'image/png' }))

    expect(mocks.uploadVariantExampleImage).toHaveBeenCalledOnce()
    expect(getPreviewSrc()).toBe(
      '/api/images/public/200/articles/mugs/variant-example-images/11111111-2222-3333-4444-555555555555.png',
    )

    const saveEvents = await submitForm(wrapper)
    expect(saveEvents).toHaveLength(1)
    expect(savedPayload(saveEvents).exampleImageFilename).toBe(
      '11111111-2222-3333-4444-555555555555.png',
    )
  })

  it('saves color picker changes from shared color inputs', async () => {
    const wrapper = await mountDialog(baseVariant)

    await setColorInput('Pick inside color', '#111111')
    await setColorInput('Pick outside color', '#222222')

    const saveEvents = await submitForm(wrapper)
    expect(savedPayload(saveEvents)).toMatchObject({
      insideColorCode: '#111111',
      outsideColorCode: '#222222',
    })
  })

  it('stages null when the image is removed', async () => {
    const wrapper = await mountDialog({ ...baseVariant, exampleImageFilename: 'old.png' })

    expect(getPreviewSrc()).toContain('old.png')

    const removeButton = document.querySelector(
      '[data-testid="variant-example-image-remove"]',
    ) as HTMLButtonElement | null
    expect(removeButton).not.toBeNull()
    removeButton!.click()
    await flushPromises()

    expect(getPreviewSrc()).toBeNull()

    const saveEvents = await submitForm(wrapper)
    expect(savedPayload(saveEvents).exampleImageFilename).toBeNull()
  })

  it('rejects unsupported file types without uploading', async () => {
    const wrapper = await mountDialog(baseVariant)

    await selectFile(new File(['text'], 'notes.txt', { type: 'text/plain' }))

    expect(mocks.uploadVariantExampleImage).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('Example image must be a PNG, JPEG, or WebP file.')
    wrapper.unmount()
  })

  it('rejects files larger than 10 MB without uploading', async () => {
    const wrapper = await mountDialog(baseVariant)

    const oversizedFile = new File(['image'], 'huge.png', { type: 'image/png' })
    Object.defineProperty(oversizedFile, 'size', { value: 10 * 1024 * 1024 + 1 })
    await selectFile(oversizedFile)

    expect(mocks.uploadVariantExampleImage).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('Example image must be at most 10 MB.')
    wrapper.unmount()
  })

  it('ignores upload results that resolve after the dialog was reset', async () => {
    let resolveUpload!: (filename: string) => void
    mocks.uploadVariantExampleImage.mockImplementation(
      () =>
        new Promise<string>((resolve) => {
          resolveUpload = resolve
        }),
    )

    const wrapper = await mountDialog(baseVariant)
    await selectFile(new File(['image'], 'mug.png', { type: 'image/png' }))

    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })
    await flushPromises()

    resolveUpload('11111111-2222-3333-4444-555555555555.png')
    await flushPromises()

    expect(getPreviewSrc()).toBeNull()

    const saveEvents = await submitForm(wrapper)
    expect(savedPayload(saveEvents).exampleImageFilename).toBeNull()
  })

  it('ignores upload errors that surface after the dialog was reset', async () => {
    let rejectUpload!: (error: Error) => void
    mocks.uploadVariantExampleImage.mockImplementation(
      () =>
        new Promise<string>((_resolve, reject) => {
          rejectUpload = reject
        }),
    )

    const wrapper = await mountDialog(baseVariant)
    await selectFile(new File(['image'], 'mug.png', { type: 'image/png' }))

    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })
    await flushPromises()

    rejectUpload(new Error('Upload failed'))
    await flushPromises()

    expect(document.body.textContent).not.toContain('Upload failed')
    wrapper.unmount()
  })

  it('keeps the previous image and shows the error when the upload fails', async () => {
    mocks.uploadVariantExampleImage.mockRejectedValue(new Error('Upload failed'))

    const wrapper = await mountDialog({ ...baseVariant, exampleImageFilename: 'old.png' })
    await selectFile(new File(['image'], 'mug.png', { type: 'image/png' }))

    expect(document.body.textContent).toContain('Upload failed')
    expect(getPreviewSrc()).toContain('old.png')

    const saveEvents = await submitForm(wrapper)
    expect(savedPayload(saveEvents).exampleImageFilename).toBe('old.png')
  })

  it('shows the message the backend put on the file field of a rejected pre-upload', async () => {
    mocks.uploadVariantExampleImage.mockRejectedValue(
      new mocks.InvalidArticleRequestError('Validation failed', {
        file: ['Image file must be at most 10 MB'],
      }),
    )

    await mountDialog()
    await selectFile(new File(['image'], 'mug.png', { type: 'image/png' }))

    expect(document.body.textContent).toContain('Image file must be at most 10 MB')
    expect(document.body.textContent).not.toContain('Validation failed')
  })
})
