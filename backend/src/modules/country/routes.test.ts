import { describe, expect, test } from 'bun:test'
import { createCountryModule } from '@/modules/country'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp } from '@test-support/http'

const database = testDatabase()
const app = () => testApp(createCountryModule({ db: database.db }).routes)
const admin = () => signedInHeaders({ roles: ['ADMIN'] })

const postCountry = async (body: string) =>
  app().request('/api/admin/countries', {
    method: 'POST',
    headers: { ...(await admin()), 'content-type': 'application/json' },
    body,
  })

describe('GET /api/countries', () => {
  test('answers the sorted public list with dial codes on its canonical path only', async () => {
    const response = await app().request('/api/countries')
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toStartWith('application/json')
    expect(await jsonOf(response)).toEqual([
      { name: 'Austria', countryCode: 'AT', dialCode: '+43' },
      { name: 'Belgium', countryCode: 'BE', dialCode: '+32' },
      { name: 'Germany', countryCode: 'DE', dialCode: '+49' },
      { name: 'Spain', countryCode: 'ES', dialCode: '+34' },
      { name: 'France', countryCode: 'FR', dialCode: '+33' },
      { name: 'Italy', countryCode: 'IT', dialCode: '+39' },
      { name: 'Netherlands', countryCode: 'NL', dialCode: '+31' },
      { name: 'Sweden', countryCode: 'SE', dialCode: '+46' },
    ])
    expect((await app().request('/API/COUNTRIES')).status).toBe(404)
    expect((await app().request('/api/countries/')).status).toBe(404)
  })
})

describe('GET /api/admin/countries', () => {
  test('answers the admin list with ids', async () => {
    const response = await app().request('/api/admin/countries', { headers: await admin() })
    expect(response.status).toBe(200)
    const countries = (await jsonOf(response)) as {
      id: number
      name: string
      countryCode: string
    }[]
    expect(countries[0]).toEqual({ id: 4, name: 'Austria', countryCode: 'AT' })
    expect(countries.map((country) => country.countryCode)).toEqual([
      'AT',
      'BE',
      'DE',
      'ES',
      'FR',
      'IT',
      'NL',
      'SE',
    ])
  })

  test('is protected: anonymous 401, non-admin 403, and a non-canonical path stays protected', async () => {
    expect((await app().request('/api/admin/countries')).status).toBe(401)
    const customer = await signedInHeaders({ roles: ['CUSTOMER'] })
    expect((await app().request('/api/admin/countries', { headers: customer })).status).toBe(403)
    expect((await app().request('/api/admin/countries/')).status).toBe(401)
    expect((await app().request('/api/admin/countries/', { headers: await admin() })).status).toBe(
      404,
    )
  })
})

describe('POST /api/admin/countries', () => {
  test('creates a normalized country and answers 201 with a Location header', async () => {
    const created = await postCountry('{"name":" Denmark ","countryCode":" dk "}')
    expect(created.status).toBe(201)
    const country = (await jsonOf(created)) as { id: number; name: string; countryCode: string }
    expect(country).toEqual({ id: country.id, name: 'Denmark', countryCode: 'DK' })
    expect(created.headers.get('Location')).toBe(`/api/admin/countries/${String(country.id)}`)
  })

  test('a duplicate name or a duplicate code answers 409', async () => {
    const sameName = await postCountry('{"name":"germany","countryCode":"GE"}')
    expect(sameName.status).toBe(409)
    expect(await jsonOf(sameName)).toEqual({
      message: 'Country name or code already exists',
      errors: {},
    })
    const sameCode = await postCountry('{"name":"Deutschland","countryCode":"de"}')
    expect(sameCode.status).toBe(409)
  })
})

describe('GET /api/admin/countries/:id', () => {
  test('answers the country, 404 for an unknown id, and 400 for a malformed one', async () => {
    const found = await app().request('/api/admin/countries/1', { headers: await admin() })
    expect(found.status).toBe(200)
    expect(await jsonOf(found)).toEqual({ id: 1, name: 'Germany', countryCode: 'DE' })
    const missing = await app().request('/api/admin/countries/999', { headers: await admin() })
    expect(missing.status).toBe(404)
    expect(await jsonOf(missing)).toEqual({ message: 'Country not found', errors: {} })
    const malformed = await app().request('/api/admin/countries/not-a-number', {
      headers: await admin(),
    })
    expect(malformed.status).toBe(400)
    expect(await jsonOf(malformed)).toEqual({ message: 'Invalid country id', errors: {} })
  })
})

describe('PUT /api/admin/countries/:id', () => {
  test('updates a country and answers 404 for an unknown id', async () => {
    const created = (await jsonOf(await postCountry('{"name":"Norway","countryCode":"NO"}'))) as {
      id: number
    }
    const put = async (id: number) =>
      app().request(`/api/admin/countries/${String(id)}`, {
        method: 'PUT',
        headers: { ...(await admin()), 'content-type': 'application/json' },
        body: '{"name":" Norge ","countryCode":" no "}',
      })
    const updated = await put(created.id)
    expect(updated.status).toBe(200)
    expect(await jsonOf(updated)).toEqual({ id: created.id, name: 'Norge', countryCode: 'NO' })
    expect((await put(999)).status).toBe(404)
  })
})

describe('DELETE /api/admin/countries/:id', () => {
  test('answers 204 without a body and then 404 for the same id', async () => {
    const created = (await jsonOf(await postCountry('{"name":"Finland","countryCode":"FI"}'))) as {
      id: number
    }
    const remove = async () =>
      app().request(`/api/admin/countries/${String(created.id)}`, {
        method: 'DELETE',
        headers: await admin(),
      })
    const deleted = await remove()
    expect(deleted.status).toBe(204)
    expect(await deleted.text()).toBe('')
    expect((await remove()).status).toBe(404)
  })
})
