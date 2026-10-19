import { beforeEach, expect, test } from 'bun:test'
import { testDatabase } from '@test-support/database'
import { createImageReferences } from './image-references'
import {
  insertCategory,
  insertSpodArticle,
  insertSpodDestination,
  insertSubcategory,
  insertSupplier,
  resetArticles,
} from './test-fixtures'

const database = testDatabase()

beforeEach(async () => {
  await resetArticles(database.db)
})

test('answers every picture a row refers to, in the folder of its kind', async () => {
  const categoryId = await insertCategory(database.db, 'Mugs')
  const subcategoryId = await insertSubcategory(database.db, categoryId, 'Classic')
  await insertSubcategory(database.db, categoryId, 'Without picture')
  await database.db
    .updateTable('article_subcategories')
    .set({ example_image_filename: 'subcategory.webp' })
    .where('id', '=', subcategoryId)
    .execute()
  const supplierId = await insertSupplier(database.db, 'Spreadconnect')
  await insertSpodArticle(database.db, {
    destinationId: await insertSpodDestination(database.db, supplierId),
    supplierId,
    sizeChart: 'chart.webp',
    colors: [{ name: 'Black', sizes: ['M'], listing: ['l1.webp', 'l2.webp'], mockup: 'm.webp' }],
  })

  expect([...(await createImageReferences(database.db)())].sort()).toEqual([
    'articles/spod/listing-images/l1.webp',
    'articles/spod/listing-images/l2.webp',
    'articles/spod/mockups/m.webp',
    'articles/spod/size-charts/chart.webp',
    'articles/subcategory-example-images/subcategory.webp',
  ])
})
