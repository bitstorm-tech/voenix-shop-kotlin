import { ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

export interface AdminSupplierCountryDto {
  id: number
  name: string
  countryCode: string
}

/**
 * The one Supplier representation of the Kotlin API: list, detail, create, and update all answer
 * this shape (`docs/dev/backend/supplier-package.md`). There is no list-only projection.
 */
export interface AdminSupplierDto {
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
  const suppliers = ref<AdminSupplierDto[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  function syncSupplier(supplier: AdminSupplierDto) {
    const index = suppliers.value.findIndex((item) => item.id === supplier.id)
    if (index === -1) {
      suppliers.value = [...suppliers.value, supplier].sort(
        (a, b) => a.name.localeCompare(b.name) || a.id - b.id,
      )
      return
    }

    suppliers.value[index] = supplier
  }

  function removeSupplier(id: number) {
    suppliers.value = suppliers.value.filter((supplier) => supplier.id !== id)
  }

  function toSupplierError(error: unknown) {
    if (!(error instanceof ApiError)) {
      return error instanceof Error ? error : new Error('Unknown error')
    }

    if (error.status === 404) {
      return new SupplierNotFoundError(error.message)
    }

    if (error.status === 409) {
      return new SupplierInUseError(error.message)
    }

    if (error.status === 400 && hasCountryNotFoundError(error)) {
      return new SupplierCountryNotFoundError(error.fieldErrors.countryId?.[0] ?? error.message)
    }

    // Everything else keeps its `ApiError`, so a caller can read the per-field validation messages.
    return error
  }

  async function fetchSuppliers() {
    if (isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      suppliers.value = await fetchJson<AdminSupplierDto[]>('/api/admin/suppliers')
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchSupplier(id: number): Promise<AdminSupplierDto> {
    try {
      const supplier = await fetchJson<AdminSupplierDto>(`/api/admin/suppliers/${id}`)
      syncSupplier(supplier)
      return supplier
    } catch (err) {
      throw toSupplierError(err)
    }
  }

  async function createSupplier(payload: CreateAdminSupplierRequest): Promise<AdminSupplierDto> {
    try {
      const supplier = await fetchJson<AdminSupplierDto>('/api/admin/suppliers', {
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
  ): Promise<AdminSupplierDto> {
    try {
      const supplier = await fetchJson<AdminSupplierDto>(`/api/admin/suppliers/${id}`, {
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

/**
 * The displayed contact person is a frontend concern: the Kotlin API answers the three name parts
 * (`title`, `firstName`, `lastName`) and never a joined string.
 */
export function formatContactPerson(supplier: AdminSupplierDto) {
  const parts = [supplier.title, supplier.firstName, supplier.lastName]
    .map((part) => part?.trim())
    .filter((part): part is string => Boolean(part))

  return parts.length === 0 ? null : parts.join(' ')
}

/**
 * An unknown `countryId` is a field error of the shared validation body
 * (`{"errors": {"countryId": ["Country not found"]}}`), not a distinct message.
 */
function hasCountryNotFoundError(error: ApiError) {
  return error.fieldErrors.countryId !== undefined
}
