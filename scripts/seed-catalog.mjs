#!/usr/bin/env bun
/**
 * Seeds a usable development catalog through the admin REST API.
 *
 * Flyway creates no catalog data — only countries, article types, and the ordering anchors — so a
 * freshly rebuilt database shows an empty shop. This script fills it with the smallest set that
 * makes every storefront screen work: one VAT entry, one supplier, a mug category with two
 * subcategories, three priced mugs with variants, and a prompt category with three priced prompts.
 *
 * It writes exclusively through `/api/admin/...`, so it can never produce a state the application
 * itself could not produce. Nothing is written to the database directly.
 *
 * ## Prerequisites
 *
 * 1. A running backend (`scripts/start-dev-server.sh`), reachable at `BASE_URL`
 *    (default `http://localhost:8080`).
 * 2. A confirmed user with the `ADMIN` role. The application seeds none; register one and grant
 *    the role in SQL as described in `docs/dev/backend/account-package.md`
 *    ("Bootstrapping the first administrator"). Pass its credentials as `ADMIN_EMAIL` and
 *    `ADMIN_PASSWORD`.
 *
 * ## Usage
 *
 * ```sh
 * ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD=secret bun scripts/seed-catalog.mjs
 * ```
 *
 * `node scripts/seed-catalog.mjs` works as well (Node 18+ has `fetch`); Bun is the repository's
 * JavaScript runtime, which is why it is the shebang.
 *
 * ## Repeatability
 *
 * Every entity is created only when no entity of the same name exists yet, so the script is safe
 * to run again after a database rebuild *and* on a database it has already seeded — the second run
 * reports what it reused. Names are the identity here because the admin contract makes them unique
 * (case-insensitively) exactly where this script relies on it.
 *
 * ## Contract notes
 *
 * - Admin lists answer bare JSON arrays; no endpoint wraps them in `{ items: [...] }`.
 * - Writes need a session cookie *and* the CSRF token from `GET /api/antiforgery/token`, sent as
 *   `X-XSRF-TOKEN`. The token is bound to the logged-in user, so it is fetched after the login.
 * - A price is embedded in the article or prompt write (`price`), never referenced by id. Both
 *   `purchaseVatId` and `salesVatId` are required.
 */

const BASE_URL = (process.env.BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '')
const ADMIN_EMAIL = process.env.ADMIN_EMAIL ?? 'admin@example.com'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? ''

/** A minimal cookie jar: `fetch` keeps no cookies, and the session lives in one. */
const cookies = new Map()
let csrfToken = null

function cookieHeader() {
  return Array.from(cookies, ([name, value]) => `${name}=${value}`).join('; ')
}

function rememberCookies(response) {
  const setCookies = response.headers.getSetCookie?.() ?? []
  for (const entry of setCookies) {
    const [pair] = entry.split(';')
    const separator = pair.indexOf('=')
    if (separator > 0) {
      cookies.set(pair.slice(0, separator).trim(), pair.slice(separator + 1).trim())
    }
  }
}

async function request(method, path, body) {
  const headers = {}
  const jar = cookieHeader()
  if (jar) headers.Cookie = jar
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (csrfToken !== null && method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'manual',
  })
  rememberCookies(response)

  const text = await response.text()
  if (!response.ok) {
    throw new Error(`${method} ${path} answered ${response.status}: ${text || '<empty body>'}`)
  }

  return text ? JSON.parse(text) : null
}

const get = (path) => request('GET', path)
const post = (path, body) => request('POST', path, body)

async function login() {
  if (!ADMIN_PASSWORD) {
    throw new Error('Set ADMIN_PASSWORD (and ADMIN_EMAIL) to the credentials of an ADMIN user.')
  }

  await post('/api/auth/login', { email: ADMIN_EMAIL, password: ADMIN_PASSWORD })
  const { requestToken } = await get('/api/antiforgery/token')
  csrfToken = requestToken

  // A non-admin session passes the login but fails every admin read with 403, which is a much
  // clearer message here than in the middle of the catalog.
  await get('/api/admin/vat')
}

/**
 * Creates the entity only when the list holds no entity of that name yet. Use this for entities
 * whose name is unique across the whole list — VATs, suppliers, categories, mugs.
 */
async function findOrCreate(label, listPath, createPath, body, name) {
  return findOrCreateMatching(label, listPath, createPath, body, name, () => true)
}

/**
 * The same, for a subcategory. A subcategory name is unique only *inside* its category, so matching
 * on the name alone would happily reuse a same-named subcategory hanging under a different parent
 * and silently seed the catalog wrong. The parent is part of the identity here.
 */
async function findOrCreateSubcategory(label, listPath, createPath, body, name) {
  return findOrCreateMatching(
    label,
    listPath,
    createPath,
    body,
    name,
    (entity) => entity.categoryId === body.categoryId,
  )
}

async function findOrCreateMatching(label, listPath, createPath, body, name, matchesParent) {
  const existing = (await get(listPath)).find(
    (entity) => entity.name?.toLowerCase() === name.toLowerCase() && matchesParent(entity),
  )
  if (existing) {
    console.log(`  reused   ${label}: ${name} (id ${existing.id})`)
    return existing
  }

  const created = await post(createPath, body)
  console.log(`  created  ${label}: ${name} (id ${created.id})`)
  return created
}

function price(vatId, purchaseCostCents, salesTotalCents) {
  return {
    purchaseVatId: vatId,
    salesVatId: vatId,
    purchaseCostInputCents: purchaseCostCents,
    salesTotalInputCents: salesTotalCents,
  }
}

function mugDetails() {
  return {
    heightMm: 95,
    diameterMm: 82,
    printTemplateWidthMm: 200,
    printTemplateHeightMm: 90,
    fillingQuantity: '325 ml',
    dishwasherSafe: true,
    documentFormatWidthMm: 200,
    documentFormatHeightMm: 90,
    documentFormatMarginBottomMm: 5,
  }
}

function mugVariants(colors) {
  return colors.map((color, index) => ({
    name: color.name,
    insideColorCode: color.inside,
    outsideColorCode: color.outside,
    isDefault: index === 0,
    active: true,
  }))
}

async function seed() {
  console.log(`Seeding the catalog at ${BASE_URL} as ${ADMIN_EMAIL}`)
  await login()

  console.log('VAT and supplier')
  const vat = await findOrCreate(
    'VAT',
    '/api/admin/vat',
    '/api/admin/vat',
    { name: 'Standard 19%', percent: 19, description: 'German standard rate', isDefault: true },
    'Standard 19%',
  )
  await findOrCreate(
    'supplier',
    '/api/admin/suppliers',
    '/api/admin/suppliers',
    {
      name: 'Voenix Development Supplier',
      city: 'Berlin',
      postalCode: '10115',
      street: 'Musterstraße',
      houseNumber: '1',
      email: 'supplier@example.com',
    },
    'Voenix Development Supplier',
  )

  console.log('Mug categories')
  const mugCategory = await findOrCreate(
    'article category',
    '/api/admin/articles/categories',
    '/api/admin/articles/categories',
    { name: 'Tassen', description: 'Alles zum Trinken', active: true },
    'Tassen',
  )
  const subcategories = {}
  for (const name of ['Klassisch', 'Thermo']) {
    subcategories[name] = await findOrCreateSubcategory(
      'article subcategory',
      '/api/admin/articles/subcategories',
      '/api/admin/articles/subcategories',
      { categoryId: mugCategory.id, name, active: true },
      name,
    )
  }

  console.log('Mugs')
  const mugs = [
    {
      name: 'Klassische Tasse Weiß',
      descriptionShort: 'Die klassische weiße Tasse',
      descriptionLong: 'Eine klassische weiße Keramiktasse mit 325 ml Füllmenge.',
      subcategory: 'Klassisch',
      purchaseCostCents: 420,
      salesTotalCents: 1490,
      colors: [
        { name: 'Weiß', inside: '#ffffff', outside: '#ffffff' },
        { name: 'Schwarz', inside: '#ffffff', outside: '#111111' },
      ],
    },
    {
      name: 'Klassische Tasse Bunt',
      descriptionShort: 'Farbige Innenseite',
      descriptionLong: 'Eine Keramiktasse mit farbiger Innenseite und weißer Außenseite.',
      subcategory: 'Klassisch',
      purchaseCostCents: 520,
      salesTotalCents: 1790,
      colors: [
        { name: 'Rot', inside: '#e2231a', outside: '#ffffff' },
        { name: 'Blau', inside: '#1c4fd8', outside: '#ffffff' },
      ],
    },
    {
      name: 'Thermobecher Edelstahl',
      descriptionShort: 'Hält lange warm',
      descriptionLong: 'Doppelwandiger Thermobecher aus Edelstahl, spülmaschinenfest.',
      subcategory: 'Thermo',
      purchaseCostCents: 890,
      salesTotalCents: 2490,
      colors: [{ name: 'Silber', inside: '#d9d9d9', outside: '#d9d9d9' }],
    },
  ]

  for (const mug of mugs) {
    await findOrCreate(
      'mug',
      '/api/admin/articles/mugs',
      '/api/admin/articles/mugs',
      {
        name: mug.name,
        descriptionShort: mug.descriptionShort,
        descriptionLong: mug.descriptionLong,
        active: true,
        categoryId: mugCategory.id,
        subcategoryId: subcategories[mug.subcategory].id,
        mugDetails: mugDetails(),
        mugVariants: mugVariants(mug.colors),
        price: price(vat.id, mug.purchaseCostCents, mug.salesTotalCents),
      },
      mug.name,
    )
  }

  console.log('Prompt categories')
  const promptCategory = await findOrCreate(
    'prompt category',
    '/api/admin/prompts/categories',
    '/api/admin/prompts/categories',
    { name: 'Porträts', active: true },
    'Porträts',
  )
  const promptSubcategory = await findOrCreateSubcategory(
    'prompt subcategory',
    '/api/admin/prompts/subcategories',
    '/api/admin/prompts/subcategories',
    { categoryId: promptCategory.id, name: 'Menschen', active: true },
    'Menschen',
  )

  console.log('Prompts')
  const prompts = [
    {
      title: 'Aquarell-Porträt',
      promptText: 'A soft watercolor portrait of the uploaded person, pastel colors, white paper.',
      salesTotalCents: 499,
    },
    {
      title: 'Comic-Porträt',
      promptText: 'A bold comic book portrait of the uploaded person, heavy ink lines, halftone.',
      salesTotalCents: 499,
    },
    {
      title: 'Ölgemälde',
      promptText: 'A classical oil painting portrait of the uploaded person, warm studio light.',
      salesTotalCents: 699,
    },
  ]

  for (const prompt of prompts) {
    const existing = (await get('/api/admin/prompts')).find(
      (row) => row.title.toLowerCase() === prompt.title.toLowerCase(),
    )
    if (existing) {
      console.log(`  reused   prompt: ${prompt.title} (id ${existing.id})`)
      continue
    }

    const created = await post('/api/admin/prompts', {
      title: prompt.title,
      promptText: prompt.promptText,
      categoryId: promptCategory.id,
      subcategoryId: promptSubcategory.id,
      slotVariantIds: [],
      llm: 'gpt-image-1',
      active: true,
      archived: false,
      price: price(vat.id, 100, prompt.salesTotalCents),
    })
    console.log(`  created  prompt: ${prompt.title} (id ${created.id})`)
  }

  const storefrontMugs = await get('/api/articles/mugs')
  const storefrontPrompts = await get('/api/prompts')
  console.log(
    `Done. The storefront now lists ${storefrontMugs.length} mugs and ${storefrontPrompts.length} prompts.`,
  )
}

seed().catch((error) => {
  console.error(`Seeding failed: ${error.message}`)
  process.exitCode = 1
})
