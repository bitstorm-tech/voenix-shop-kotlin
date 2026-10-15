import { conflict, notFound, ok, type OperationResult } from '@/platform/operation'
import type { DiscountType, Promotion, PromotionRepository } from './repository'

/**
 * The validated body of a create or update, as `routes.ts`'s schema produces it: text trimmed,
 * timestamps parsed, every optional field `null` when absent.
 */
export interface PromotionInput {
  name: string
  couponCode: string
  discountType: DiscountType
  discountValue: number
  startsAt: Date | null
  endsAt: Date | null
  usageLimitTotal: number | null
  usageLimitPerUser: number | null
  isActive: boolean
}

export type PromotionService = ReturnType<typeof createPromotionService>

/** The admin use cases. `update` answers `conflict` for a duplicate code and for a locked promotion. */
export function createPromotionService(repository: PromotionRepository) {
  return {
    list: async (): Promise<OperationResult<Promotion[]>> => ok(await repository.list()),

    get: async (id: number): Promise<OperationResult<Promotion>> => {
      const promotion = await repository.find(id)
      return promotion === undefined ? notFound : ok(promotion)
    },

    create: async (input: PromotionInput): Promise<OperationResult<Promotion>> => {
      const stored = await repository.insert(input)
      return stored === 'conflict' ? conflict : ok(stored)
    },

    update: async (id: number, input: PromotionInput): Promise<OperationResult<Promotion>> => {
      const stored = await repository.update(id, input)
      if (stored === 'notFound') return notFound
      if (stored === 'conflict' || stored === 'locked') return conflict
      return ok(stored)
    },

    delete: async (id: number): Promise<OperationResult<undefined>> => {
      const deleted = await repository.delete(id)
      if (deleted === 'inUse') return conflict
      return deleted === 0 ? notFound : ok(undefined)
    },
  }
}
