import { ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

export interface AdminVatDto {
  id: number
  name: string
  percent: number
  description: string | null
  isDefault: boolean
}

export interface CreateAdminVatRequest {
  name: string
  percent: number
  description?: string | null
  isDefault: boolean
}

export interface UpdateAdminVatRequest {
  name: string
  percent: number
  description?: string | null
  isDefault: boolean
}

export class VatNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'VatNotFoundError'
  }
}

export class VatNameConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'VatNameConflictError'
  }
}

export const useAdminVatStore = defineStore('admin-vat', () => {
  const vats = ref<AdminVatDto[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  function syncVat(vat: AdminVatDto) {
    const index = vats.value.findIndex((item) => item.id === vat.id)
    if (index === -1) {
      vats.value = [...vats.value, vat]
      return
    }

    vats.value[index] = vat
  }

  function removeVat(id: number) {
    vats.value = vats.value.filter((vat) => vat.id !== id)
  }

  async function fetchAll() {
    if (isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      const data = await fetchJson<AdminVatDto[]>('/api/admin/vat')
      vats.value = data
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchById(id: number): Promise<AdminVatDto> {
    try {
      const vat = await fetchJson<AdminVatDto>(`/api/admin/vat/${id}`)
      syncVat(vat)
      return vat
    } catch (err) {
      throw toVatError(err)
    }
  }

  async function createVat(payload: CreateAdminVatRequest): Promise<AdminVatDto> {
    try {
      const vat = await fetchJson<AdminVatDto>('/api/admin/vat', {
        method: 'POST',
        body: payload,
      })
      syncVat(vat)
      return vat
    } catch (err) {
      throw toVatError(err)
    }
  }

  async function updateVat(id: number, payload: UpdateAdminVatRequest): Promise<AdminVatDto> {
    try {
      const vat = await fetchJson<AdminVatDto>(`/api/admin/vat/${id}`, {
        method: 'PUT',
        body: payload,
      })
      syncVat(vat)
      return vat
    } catch (err) {
      throw toVatError(err)
    }
  }

  async function deleteVat(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/vat/${id}`, { method: 'DELETE', responseType: 'void' })
      removeVat(id)
    } catch (err) {
      throw toVatError(err)
    }
  }

  return {
    vats,
    isLoading,
    error,
    fetchAll,
    fetchById,
    createVat,
    updateVat,
    deleteVat,
  }
})

function toVatError(error: unknown) {
  const message = error instanceof Error ? error.message : 'Unknown error'

  if (error instanceof ApiError && error.status === 404) {
    return new VatNotFoundError(message)
  }

  if (error instanceof ApiError && error.status === 409) {
    return new VatNameConflictError(message)
  }

  return new Error(message)
}
