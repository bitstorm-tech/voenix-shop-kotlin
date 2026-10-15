import { describe, expect, test } from 'bun:test'
import type { z } from 'zod'
import { categoryInput, subcategoryInput } from '@/modules/prompt/category/routes'
import { reorderInput } from '@/modules/prompt/fields'
import { promptInput } from '@/modules/prompt/prompt/routes'
import { slotInput, slotVariantInput, slotVariantUpdate } from '@/modules/prompt/slot/routes'
import { validationFailed } from '@/platform/http/respond'

const errorsOf = (schema: z.ZodType, input: unknown) => {
  const result = schema.safeParse(input)
  return result.success ? {} : validationFailed(result.error).errors
}

const PRICE = { purchaseVatId: 1, salesVatId: 1 }

describe('promptInput', () => {
  const valid = {
    title: '  Watercolor portrait  ',
    promptText: '\n  Paint it.  \n',
    categoryId: 3,
    slotVariantIds: [12, 9, 12],
    exampleImageFilename: '   ',
    llm: ' gpt-image-1 ',
    price: PRICE,
  }

  test('trims title and optional texts, keeps the prompt text verbatim, and deduplicates the variants', () => {
    expect(promptInput.parse(valid)).toMatchObject({
      title: 'Watercolor portrait',
      promptText: '\n  Paint it.  \n',
      categoryId: 3,
      subcategoryId: null,
      slotVariantIds: [12, 9],
      exampleImageFilename: null,
      llm: 'gpt-image-1',
      active: false,
      archived: false,
      price: { purchaseVatId: 1, salesVatId: 1, salesTotalInputCents: 0 },
    })
  })

  test('reports every broken field with the documented message', () => {
    expect(errorsOf(promptInput, {})).toEqual({
      title: ['Title is required'],
      promptText: ['PromptText is required'],
      categoryId: ['CategoryId is required'],
      slotVariantIds: ['SlotVariantIds is required'],
      price: ['Price is required'],
    })
    expect(
      errorsOf(promptInput, {
        ...valid,
        title: 'a'.repeat(256),
        promptText: ' \n ',
        categoryId: 0,
        subcategoryId: -1,
        slotVariantIds: [1, 0],
        exampleImageFilename: 'a'.repeat(256),
        llm: 'a'.repeat(256),
        price: { ...PRICE, salesVatId: 0 },
      }),
    ).toEqual({
      title: ['Title must be at most 255 characters'],
      promptText: ['PromptText is required'],
      categoryId: ['CategoryId must be positive'],
      subcategoryId: ['SubcategoryId must be positive'],
      slotVariantIds: ['SlotVariantIds must be positive'],
      exampleImageFilename: ['ExampleImageFilename must be at most 255 characters'],
      llm: ['Llm must be at most 255 characters'],
      'price.salesVatId': ['Sales VAT id is required'],
    })
    expect(errorsOf(promptInput, { ...valid, position: 9, priceId: 77 })).toEqual({
      position: ['Unknown property'],
      priceId: ['Unknown property'],
    })
    expect(errorsOf(promptInput, { ...valid, title: ` ${'a'.repeat(255)} ` })).toEqual({})
  })
})

describe('reorderInput', () => {
  test('requires two different positive ids', () => {
    expect(reorderInput.parse({ sourceId: 42, targetId: 8 })).toEqual({ sourceId: 42, targetId: 8 })
    expect(errorsOf(reorderInput, {})).toEqual({
      sourceId: ['SourceId is required'],
      targetId: ['TargetId is required'],
    })
    expect(errorsOf(reorderInput, { sourceId: 7, targetId: 7 })).toEqual({
      targetId: ['TargetId must be different from SourceId'],
    })
    expect(errorsOf(reorderInput, { sourceId: 0, targetId: 0 })).toEqual({
      sourceId: ['SourceId must be positive'],
      targetId: ['TargetId must be positive', 'TargetId must be different from SourceId'],
    })
  })
})

describe('slotInput', () => {
  test('requires a trimmed name of at most 255 characters', () => {
    expect(slotInput.parse({ name: ' Style ' })).toEqual({ name: 'Style' })
    expect(errorsOf(slotInput, { name: '   ' })).toEqual({ name: ['Name is required'] })
    expect(errorsOf(slotInput, { name: 'a'.repeat(256) })).toEqual({
      name: ['Name must be at most 255 characters'],
    })
  })
})

describe('slotVariantInput and slotVariantUpdate', () => {
  test('share the field rules; only the create carries the slot', () => {
    expect(
      slotVariantInput.parse({ slotId: 2, name: ' Meadow ', prompt: ' in a meadow ', llm: ' ' }),
    ).toEqual({ slotId: 2, name: 'Meadow', prompt: 'in a meadow', description: null, llm: null })
    expect(errorsOf(slotVariantInput, {})).toEqual({
      slotId: ['Slot id is required'],
      name: ['Name is required'],
      prompt: ['Prompt is required'],
    })
    expect(
      errorsOf(slotVariantInput, {
        slotId: 0,
        name: 'n',
        prompt: 'a'.repeat(10_001),
        description: 'a'.repeat(1001),
        llm: 'a'.repeat(256),
      }),
    ).toEqual({
      slotId: ['Slot id must be positive'],
      prompt: ['Prompt must be at most 10000 characters'],
      description: ['Description must be at most 1000 characters'],
      llm: ['LLM must be at most 255 characters'],
    })
    expect(errorsOf(slotVariantUpdate, { slotId: 2, name: 'n', prompt: 'p' })).toEqual({
      slotId: ['Unknown property'],
    })
  })
})

describe('categoryInput and subcategoryInput', () => {
  test('a category needs a name of at most 200 characters and is active unless told otherwise', () => {
    expect(categoryInput.parse({ name: ' Portraits ' })).toEqual({
      name: 'Portraits',
      active: true,
    })
    expect(errorsOf(categoryInput, { name: 'a'.repeat(201), active: 'yes' })).toEqual({
      name: ['Name must be at most 200 characters'],
      active: ['Active must be a boolean'],
    })
  })

  test('a subcategory also needs its category and allows a description of 1000 characters', () => {
    expect(subcategoryInput.parse({ categoryId: 1, name: ' Adults ', description: ' ' })).toEqual({
      categoryId: 1,
      name: 'Adults',
      description: null,
      active: true,
    })
    expect(errorsOf(subcategoryInput, { description: 'a'.repeat(1001) })).toEqual({
      categoryId: ['CategoryId is required'],
      name: ['Name is required'],
      description: ['Description must be at most 1000 characters'],
    })
    expect(errorsOf(subcategoryInput, { categoryId: -1, name: 'n' })).toEqual({
      categoryId: ['CategoryId must be positive'],
    })
  })
})
