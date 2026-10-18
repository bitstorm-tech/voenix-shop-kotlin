import { describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { createCountryModule } from '@/modules/country'
import { createSupplierModule } from '@/modules/supplier'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp } from '@test-support/http'

const database = testDatabase()
const app = () =>
  testApp(
    createSupplierModule({ db: database.db, countries: createCountryModule({ db: database.db }) })
      .routes,
  )
const admin = () => signedInHeaders({ roles: ['ADMIN'] })

const send = async (method: string, path: string, body?: string) =>
  app().request(path, {
    method,
    headers: { ...(await admin()), 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body }),
  })
const create = async (body: string) =>
  (await jsonOf(await send('POST', '/api/admin/suppliers', body))) as { id: number }

const germany = { id: 1, name: 'Germany', countryCode: 'DE' }
const emptySupplier = {
  title: null,
  firstName: null,
  lastName: null,
  street: null,
  houseNumber: null,
  city: null,
  postalCode: null,
  countryId: null,
  country: null,
  phoneNumber1: null,
  phoneNumber2: null,
  phoneNumber3: null,
  email: null,
  website: null,
}

describe('POST /api/admin/suppliers', () => {
  test('creates a normalized supplier with its country and answers 201 with a Location header', async () => {
    const created = await send(
      'POST',
      '/api/admin/suppliers',
      '{"name":" Acme ","firstName":" ","city":" Berlin ","countryId":1,"email":" ada@example.test "}',
    )
    expect(created.status).toBe(201)
    const supplier = await jsonOf(created)
    const { id } = supplier as { id: number }
    expect(supplier).toEqual({
      ...emptySupplier,
      id,
      name: 'Acme',
      city: 'Berlin',
      countryId: 1,
      country: germany,
      email: 'ada@example.test',
    })
    expect(created.headers.get('Location')).toBe(`/api/admin/suppliers/${String(id)}`)
  })

  test('an unknown country answers 400 on the countryId field and stores nothing', async () => {
    const before = await jsonOf(await send('GET', '/api/admin/suppliers'))
    const rejected = await send(
      'POST',
      '/api/admin/suppliers',
      '{"name":"Nowhere","countryId":999}',
    )
    expect(rejected.status).toBe(400)
    expect(await jsonOf(rejected)).toEqual({
      message: 'Validation failed',
      errors: { countryId: ['Country not found'] },
    })
    expect(await jsonOf(await send('GET', '/api/admin/suppliers'))).toEqual(before)
  })
})

describe('GET /api/admin/suppliers', () => {
  test('answers the suppliers ordered by name, then id, each with its country', async () => {
    const zulu = await create('{"name":"Zulu","countryId":2}')
    const acme = await create('{"name":"Aaron","countryId":1}')
    const secondAcme = await create('{"name":"Aaron"}')
    const response = await send('GET', '/api/admin/suppliers')
    expect(response.status).toBe(200)
    const listed = (await jsonOf(response)) as { id: number; name: string; country: unknown }[]
    const ours = listed.filter((supplier) =>
      [zulu.id, acme.id, secondAcme.id].includes(supplier.id),
    )
    expect(ours.map(({ id, country }) => ({ id, country }))).toEqual([
      { id: acme.id, country: germany },
      { id: secondAcme.id, country: null },
      { id: zulu.id, country: { id: 2, name: 'France', countryCode: 'FR' } },
    ])
  })
})

describe('GET /api/admin/suppliers/:id', () => {
  test('answers the supplier and 404 for an unknown id', async () => {
    const { id } = await create('{"name":"Initech"}')
    const found = await send('GET', `/api/admin/suppliers/${String(id)}`)
    expect(found.status).toBe(200)
    expect(await jsonOf(found)).toEqual({ ...emptySupplier, id, name: 'Initech' })
    const missing = await send('GET', '/api/admin/suppliers/999')
    expect(missing.status).toBe(404)
    expect(await jsonOf(missing)).toEqual({ message: 'Supplier not found', errors: {} })
  })
})

describe('PUT /api/admin/suppliers/:id', () => {
  test('replaces every field, keeps the row on an unknown country, and answers 404 for an unknown id', async () => {
    const { id } = await create('{"name":"Acme","title":"Dr.","city":"Berlin","countryId":1}')
    const path = `/api/admin/suppliers/${String(id)}`
    const replaced = await send('PUT', path, '{"name":" Globex ","countryId":2}')
    expect(replaced.status).toBe(200)
    const globex = {
      ...emptySupplier,
      id,
      name: 'Globex',
      countryId: 2,
      country: { id: 2, name: 'France', countryCode: 'FR' },
    }
    expect(await jsonOf(replaced)).toEqual(globex)

    const unknownCountry = await send('PUT', path, '{"name":"Rolled back","countryId":999}')
    expect(unknownCountry.status).toBe(400)
    expect(await jsonOf(unknownCountry)).toEqual({
      message: 'Validation failed',
      errors: { countryId: ['Country not found'] },
    })
    expect(await jsonOf(await send('GET', path))).toEqual(globex)

    expect((await send('PUT', '/api/admin/suppliers/999', '{"name":"Missing"}')).status).toBe(404)
  })
})

describe('DELETE /api/admin/suppliers/:id', () => {
  test('answers 204 without a body and then 404 for the same id', async () => {
    const { id } = await create('{"name":"Short-lived"}')
    const deleted = await send('DELETE', `/api/admin/suppliers/${String(id)}`)
    expect(deleted.status).toBe(204)
    expect(await deleted.text()).toBe('')
    expect((await send('DELETE', `/api/admin/suppliers/${String(id)}`)).status).toBe(404)
  })

  test('a supplier a production destination references answers 409 and stays', async () => {
    const { id } = await create('{"name":"Producer"}')
    await sql`INSERT INTO production_destinations (supplier_id, channel, label)
      VALUES (${id}, 'SFTP', 'Producer drop')`.execute(database.db)
    const path = `/api/admin/suppliers/${String(id)}`
    const refused = await send('DELETE', path)
    expect(refused.status).toBe(409)
    expect(await jsonOf(refused)).toEqual({
      message: 'Supplier is in use and cannot be deleted',
      errors: {},
    })
    expect((await send('GET', path)).status).toBe(200)

    await sql`DELETE FROM production_destinations WHERE supplier_id = ${id}`.execute(database.db)
    expect((await send('DELETE', path)).status).toBe(204)
  })
})
