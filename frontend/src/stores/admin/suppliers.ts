import { ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

export interface AdminSupplierCountryDto {
  id: number
  name: string
  countryCode: string
}

export interface AdminSupplierListItemDto {
  id: number
  name: string
  contactPerson: string | null
  city: string | null
  country: AdminSupplierCountryDto | null
  email: string | null
}

export interface AdminSupplierDetailDto {
  id: number
  name: string
  title: string | null
  firstName: string | null
  lastName: string | null
  street: string | null
  houseNumber: string | null
  city: string | null
  postalCode: string | null
  countryId: number | null
  country: AdminSupplierCountryDto | null
  phoneNumber1: string | null
  phoneNumber2: string | null
  phoneNumber3: string | null
  email: string | null
  website: string | null
}

export interface CreateAdminSupplierRequest {
  name: string
  title?: string | null
  firstName?: string | null
  lastName?: string | null
  street?: string | null
  houseNumber?: string | null
  city?: string | null
  postalCode?: string | null
  countryId?: number | null
  phoneNumber1?: string | null
  phoneNumber2?: string | null
  phoneNumber3?: string | null
  email?: string | null
  website?: string | null
}

export interface UpdateAdminSupplierRequest {
  name?: string | null
  title?: string | null
  firstName?: string | null
  lastName?: string | null
  street?: string | null
  houseNumber?: string | null
  city?: string | null
  postalCode?: string | null
  countryId?: number | null
  phoneNumber1?: string | null
  phoneNumber2?: string | null
  phoneNumber3?: string | null
  email?: string | null
  website?: string | null
}

export class SupplierNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SupplierNotFoundError'
  }
}

export class SupplierInUseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SupplierInUseError'
  }
}

export class SupplierCountryNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SupplierCountryNotFoundError'
  }
}

export const useAdminSuppliersStore = defineStore('admin-suppliers', () => {
  const suppliers = ref<AdminSupplierListItemDto[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  function syncSupplier(supplier: AdminSupplierDetailDto) {
    const listItem: AdminSupplierListItemDto = {
      id: supplier.id,
      name: supplier.name,
      contactPerson: formatContactPerson(supplier),
      city: supplier.city,
      country: supplier.country,
      email: supplier.email,
    }

    const index = suppliers.value.findIndex((item) => item.id === supplier.id)
    if (index === -1) {
      suppliers.value = [...suppliers.value, listItem].sort(
        (a, b) => a.name.localeCompare(b.name) || a.id - b.id,
      )
      return
    }

    suppliers.value[index] = listItem
  }

  function removeSupplier(id: number) {
    suppliers.value = suppliers.value.filter((supplier) => supplier.id !== id)
  }

  function toSupplierError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new SupplierNotFoundError(message)
    }

    if (error.status === 409) {
      return new SupplierInUseError(message)
    }

    if (error.status === 400 && isSupplierCountryNotFoundMessage(message)) {
      return new SupplierCountryNotFoundError(message)
    }

    return new Error(message)
  }

  async function fetchSuppliers() {
    if (isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      const data = await fetchJson<{ items: AdminSupplierListItemDto[] }>('/api/admin/suppliers')
      suppliers.value = data.items
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchSupplier(id: number): Promise<AdminSupplierDetailDto> {
    try {
      const supplier = await fetchJson<AdminSupplierDetailDto>(`/api/admin/suppliers/${id}`)
      syncSupplier(supplier)
      return supplier
    } catch (err) {
      throw toSupplierError(err)
    }
  }

  async function createSupplier(
    payload: CreateAdminSupplierRequest,
  ): Promise<AdminSupplierDetailDto> {
    try {
      const supplier = await fetchJson<AdminSupplierDetailDto>('/api/admin/suppliers', {
        method: 'POST',
        body: payload,
      })
      syncSupplier(supplier)
      return supplier
    } catch (err) {
      throw toSupplierError(err)
    }
  }

  async function updateSupplier(
    id: number,
    payload: UpdateAdminSupplierRequest,
  ): Promise<AdminSupplierDetailDto> {
    try {
      const supplier = await fetchJson<AdminSupplierDetailDto>(`/api/admin/suppliers/${id}`, {
        method: 'PUT',
        body: payload,
      })
      syncSupplier(supplier)
      return supplier
    } catch (err) {
      throw toSupplierError(err)
    }
  }

  async function deleteSupplier(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/suppliers/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      removeSupplier(id)
    } catch (err) {
      throw toSupplierError(err)
    }
  }

  return {
    suppliers,
    isLoading,
    error,
    fetchSuppliers,
    fetchSupplier,
    createSupplier,
    updateSupplier,
    deleteSupplier,
  }
})

function formatContactPerson(supplier: AdminSupplierDetailDto) {
  const parts = [supplier.title, supplier.firstName, supplier.lastName]
    .map((part) => part?.trim())
    .filter((part): part is string => Boolean(part))

  return parts.length === 0 ? null : parts.join(' ')
}

function isSupplierCountryNotFoundMessage(message: string) {
  const normalizedMessage = message.toLowerCase()
  return normalizedMessage.includes('country') && normalizedMessage.includes('not found')
}
