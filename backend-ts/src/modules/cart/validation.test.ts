import { describe, expect, test } from 'bun:test'
import type { z } from 'zod'
import { validationFailed } from '@/platform/http/respond'
import { addCartItemInput, cartQuantityInput, promotionCodeInput } from './routes'

const errorsOf = (schema: z.ZodType, input: unknown) => {
  const result = schema.safeParse(input)
  return result.success ? {} : validationFailed(result.error).errors
}

describe('addCartItemInput', () => {
  test('valid input is accepted, with or without prompt and image', () => {
    expect(addCartItemInput.parse({ articleId: 1, variantId: 2, quantity: 99 })).toEqual({
      articleId: 1,
      variantId: 2,
      quantity: 99,
    })
    expect(
      addCartItemInput.parse({
        articleId: 1,
        variantId: 2,
        quantity: 1,
        promptId: null,
        imageId: 3,
      }),
    ).toEqual({ articleId: 1, variantId: 2, quantity: 1, promptId: null, imageId: 3 })
  })

  test('every field reports its required, positive, and range message', () => {
    expect(errorsOf(addCartItemInput, {})).toEqual({
      articleId: ['ArticleId is required'],
      variantId: ['VariantId is required'],
      quantity: ['Quantity is required'],
    })
    expect(
      errorsOf(addCartItemInput, {
        articleId: 0,
        variantId: -1,
        quantity: 0,
        promptId: 0,
        imageId: -5,
      }),
    ).toEqual({
      articleId: ['ArticleId must be positive'],
      variantId: ['VariantId must be positive'],
      quantity: ['Quantity must be between 1 and 99'],
      promptId: ['PromptId must be positive'],
      imageId: ['ImageId must be positive'],
    })
    expect(errorsOf(addCartItemInput, { articleId: 1, variantId: 1, quantity: 100 })).toEqual({
      quantity: ['Quantity must be between 1 and 99'],
    })
  })
})

describe('cartQuantityInput', () => {
  test('accepts 1 to 99 and reports everything else', () => {
    expect(cartQuantityInput.parse({ quantity: 1 })).toEqual({ quantity: 1 })
    expect(errorsOf(cartQuantityInput, {})).toEqual({ quantity: ['Quantity is required'] })
    expect(errorsOf(cartQuantityInput, { quantity: 100 })).toEqual({
      quantity: ['Quantity must be between 1 and 99'],
    })
    expect(errorsOf(cartQuantityInput, { quantity: 0 })).toEqual({
      quantity: ['Quantity must be between 1 and 99'],
    })
  })
})

describe('promotionCodeInput', () => {
  test('trims the code, and a blank or overlong one is refused', () => {
    expect(promotionCodeInput.parse({ promotionCode: ' save10 ' })).toEqual({
      promotionCode: 'save10',
    })
    expect(errorsOf(promotionCodeInput, {})).toEqual({
      promotionCode: ['PromotionCode is required'],
    })
    expect(errorsOf(promotionCodeInput, { promotionCode: '   ' })).toEqual({
      promotionCode: ['PromotionCode is required'],
    })
    expect(errorsOf(promotionCodeInput, { promotionCode: ` ${'A'.repeat(65)} ` })).toEqual({
      promotionCode: ['PromotionCode must be at most 64 characters'],
    })
  })
})
