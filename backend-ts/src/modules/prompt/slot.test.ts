import { afterAll, beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { sql } from 'kysely'
import { createImageModule } from '@/modules/image'
import { createPricingModule } from '@/modules/pricing'
import { createPromptModule } from '@/modules/prompt'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'

const database = testDatabase()
let directory: string
beforeAll(async () => {
  directory = await mkdtemp(join(tmpdir(), 'prompt-slots-'))
})
afterAll(() => rm(directory, { recursive: true, force: true }))

const app = () => {
  const images = createImageModule({
    settings: {
      publicRoot: join(directory, 'public'),
      privateRoot: join(directory, 'private'),
      cacheRoot: join(directory, 'cache'),
    },
    guestTokens: testAuth.guestTokens,
  })
  const { priceCatalog } = createPricingModule({ db: database.db })
  return testApp(
    createPromptModule({ db: database.db, priceCatalog, exampleImages: images.exampleImages })
      .routes,
  )
}

const call = async (method: string, path: string, body?: unknown) =>
  app().request(`/api/admin/prompts${path}`, {
    method,
    headers: { ...(await signedInHeaders()), 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })

const created = async (path: string, body: unknown) => {
  const response = await call('POST', path, body)
  expect(response.status).toBe(201)
  return (await jsonOf(response)) as { id: number }
}

beforeEach(async () => {
  await sql`TRUNCATE prompt_slots, prompt_slot_variants, prompts, prompt_slot_variant_mappings,
    prompt_categories, prompt_subcategories, prices, value_added_taxes RESTART IDENTITY CASCADE`.execute(
    database.db,
  )
})

describe('the slot routes', () => {
  test('create appends with a Location header; list, get, update, and delete answer the documented shapes', async () => {
    const response = await call('POST', '/slots', { name: ' Background ' })
    expect(response.status).toBe(201)
    expect(response.headers.get('Location')).toBe('/api/admin/prompts/slots/1')
    expect(await jsonOf(response)).toEqual({
      id: 1,
      name: 'Background',
      position: 1,
      variantCount: 0,
    })
    await created('/slots', { name: 'Style' })
    await created('/slot-variants', { slotId: 2, name: 'Watercolor', prompt: 'in watercolor' })

    expect(await jsonOf(await call('GET', '/slots'))).toEqual([
      { id: 1, name: 'Background', position: 1, variantCount: 0 },
      { id: 2, name: 'Style', position: 2, variantCount: 1 },
    ])
    expect(await jsonOf(await call('GET', '/slots/2'))).toEqual({
      id: 2,
      name: 'Style',
      position: 2,
      variantCount: 1,
    })
    expect(await jsonOf(await call('PUT', '/slots/1', { name: 'Scene' }))).toEqual({
      id: 1,
      name: 'Scene',
      position: 1,
      variantCount: 0,
    })
    const deleted = await call('DELETE', '/slots/1')
    expect(deleted.status).toBe(204)
    expect(await deleted.text()).toBe('')
  })

  test('a deleted position stays empty: the next create appends behind the highest one', async () => {
    await created('/slots', { name: 'A' })
    await created('/slots', { name: 'B' })
    expect((await call('DELETE', '/slots/2')).status).toBe(204)
    expect(await jsonOf(await call('POST', '/slots', { name: 'C' }))).toMatchObject({ position: 2 })
    expect((await call('DELETE', '/slots/1')).status).toBe(204)
    expect(await jsonOf(await call('POST', '/slots', { name: 'D' }))).toMatchObject({ position: 3 })
  })

  test('a duplicate name in any case is 409, a slot with variants cannot be deleted, unknown ids are 404', async () => {
    await created('/slots', { name: 'Background' })
    const duplicate = await call('POST', '/slots', { name: 'BACKGROUND' })
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({
      message: 'Prompt slot name already exists',
      errors: {},
    })
    const style = await created('/slots', { name: 'Style' })
    expect((await call('PUT', `/slots/${String(style.id)}`, { name: 'background' })).status).toBe(
      409,
    )

    await created('/slot-variants', { slotId: 1, name: 'Meadow', prompt: 'in a meadow' })
    const inUse = await call('DELETE', '/slots/1')
    expect(inUse.status).toBe(409)
    expect(await jsonOf(inUse)).toEqual({
      message: 'Prompt slot is used by slot variants and cannot be deleted',
      errors: {},
    })

    const missing = await call('GET', '/slots/99')
    expect(missing.status).toBe(404)
    expect(await jsonOf(missing)).toEqual({ message: 'Prompt slot not found', errors: {} })
    expect((await call('PUT', '/slots/99', { name: 'X' })).status).toBe(404)
    expect((await call('DELETE', '/slots/99')).status).toBe(404)
    expect(await jsonOf(await call('GET', '/slots/x'))).toEqual({
      message: 'Invalid prompt slot id',
      errors: {},
    })
  })

  test('concurrent creates append 1..n, and of two case variants of one name exactly one is stored', async () => {
    const appended = await Promise.all(
      ['A', 'B', 'C', 'D'].map((name) => call('POST', '/slots', { name })),
    )
    expect(appended.map((response) => response.status)).toEqual([201, 201, 201, 201])
    const positions = (await jsonOf(await call('GET', '/slots'))) as { position: number }[]
    expect(positions.map((slot) => slot.position)).toEqual([1, 2, 3, 4])

    const raced = await Promise.all([
      call('POST', '/slots', { name: 'Mood' }),
      call('POST', '/slots', { name: 'MOOD' }),
    ])
    expect(raced.map((response) => response.status).sort()).toEqual([201, 409])
  })
})

describe('the slot-variant routes', () => {
  beforeEach(async () => {
    await created('/slots', { name: 'Style' })
    await created('/slots', { name: 'Background' })
  })

  test('create, list in slot order then by name, get, update without a slot, and delete', async () => {
    const response = await call('POST', '/slot-variants', {
      slotId: 2,
      name: ' Meadow ',
      prompt: '  in a meadow  ',
      description: '   ',
      llm: ' gpt-image-1 ',
    })
    expect(response.status).toBe(201)
    expect(response.headers.get('Location')).toBe('/api/admin/prompts/slot-variants/1')
    expect(await jsonOf(response)).toEqual({
      id: 1,
      slotId: 2,
      slotName: 'Background',
      name: 'Meadow',
      prompt: 'in a meadow',
      description: null,
      llm: 'gpt-image-1',
      assignedPromptCount: 0,
    })
    await created('/slot-variants', { slotId: 1, name: 'Watercolor', prompt: 'in watercolor' })
    await created('/slot-variants', { slotId: 1, name: 'Oil', prompt: 'in oil' })

    const listed = (await jsonOf(await call('GET', '/slot-variants'))) as { name: string }[]
    expect(listed.map((variant) => variant.name)).toEqual(['Oil', 'Watercolor', 'Meadow'])
    expect(await jsonOf(await call('GET', '/slot-variants/3'))).toMatchObject({
      name: 'Oil',
      slotName: 'Style',
    })
    expect(
      await jsonOf(
        await call('PUT', '/slot-variants/3', {
          name: 'Oil paint',
          prompt: 'in oil paint',
          description: 'Thick',
          llm: null,
        }),
      ),
    ).toEqual({
      id: 3,
      slotId: 1,
      slotName: 'Style',
      name: 'Oil paint',
      prompt: 'in oil paint',
      description: 'Thick',
      llm: null,
      assignedPromptCount: 0,
    })
    expect((await call('DELETE', '/slot-variants/3')).status).toBe(204)
    expect((await call('GET', '/slot-variants/3')).status).toBe(404)
  })

  test('names are unique across all slots, an unknown slot is a field error, a used variant cannot be deleted', async () => {
    await created('/slot-variants', { slotId: 1, name: 'Meadow', prompt: 'in a meadow' })
    const duplicate = await call('POST', '/slot-variants', {
      slotId: 2,
      name: 'MEADOW',
      prompt: 'p',
    })
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({
      message: 'Prompt slot variant name already exists',
      errors: {},
    })
    const unknownSlot = await call('POST', '/slot-variants', { slotId: 99, name: 'X', prompt: 'p' })
    expect(unknownSlot.status).toBe(400)
    expect(await jsonOf(unknownSlot)).toEqual({
      message: 'Validation failed',
      errors: { slotId: ['Prompt slot does not exist'] },
    })

    await sql`INSERT INTO prompt_categories (name, position, active) VALUES ('Portraits', 1, true)`.execute(
      database.db,
    )
    await sql`INSERT INTO prompts (position, title, prompt_text, category_id, active, archived)
      VALUES (1, 'P', 'text', 1, true, false)`.execute(database.db)
    await sql`INSERT INTO prompt_slot_variant_mappings (prompt_id, slot_variant_id) VALUES (1, 1)`.execute(
      database.db,
    )
    expect(await jsonOf(await call('GET', '/slot-variants/1'))).toMatchObject({
      assignedPromptCount: 1,
    })
    const inUse = await call('DELETE', '/slot-variants/1')
    expect(inUse.status).toBe(409)
    expect(await jsonOf(inUse)).toEqual({
      message: 'Prompt slot variant is used by prompts and cannot be deleted',
      errors: {},
    })
    expect(await jsonOf(await call('GET', '/slot-variants/x'))).toEqual({
      message: 'Invalid prompt slot variant id',
      errors: {},
    })
  })

  test('of two concurrent creates with case variants of one name in two slots, one is stored', async () => {
    const raced = await Promise.all([
      call('POST', '/slot-variants', { slotId: 1, name: 'Dusk', prompt: 'at dusk' }),
      call('POST', '/slot-variants', { slotId: 2, name: 'dusk', prompt: 'at dusk' }),
    ])
    expect(raced.map((response) => response.status).sort()).toEqual([201, 409])
    expect(await jsonOf(await call('GET', '/slot-variants'))).toHaveLength(1)
  })
})
