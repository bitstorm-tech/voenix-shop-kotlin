import { Hono } from 'hono'
import type { Kysely } from 'kysely'
import type { ImageModule } from '@/modules/image'
import type { PriceCatalog } from '@/modules/pricing'
import type { SupplierModule } from '@/modules/supplier'
import type { SpodClient } from '@/modules/spod'
import type { Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'
import { createArticleCatalog } from './catalog'
import { createCategoryRepository } from './category/repository'
import { categoryRoutes } from './category/routes'
import { createCategoryService } from './category/service'
import { createMugRepository } from './mug/repository'
import { mugRoutes } from './mug/routes'
import { createMugService } from './mug/service'
import { storefrontRoutes } from './routes'
import { createSpodArticleRepository } from './spod-article/repository'
import { spodArticleRoutes } from './spod-article/routes'
import { createSpodArticleService } from './spod-article/service'
import { createSpodCatalogSync } from './spod-article/sync'
import { createSyncRepository } from './spod-article/sync-repository'
import { createStorefront } from './storefront'

export type {
  ArticleCatalog,
  ArticleType,
  ArticleVariantReference,
  CatalogVariant,
  PrintAspectRatio,
  SpodProductRef,
} from './catalog'
export { catalogKey, spodVariantName } from './catalog'
export type { PrintFrame } from './spod-article/repository'
export type {
  SpodCatalogSource,
  SpodCatalogSync,
  SpodSyncLine,
  SpodSyncReport,
  SpodSyncResult,
  SpodSyncWarning,
  SpodSyncWarningCode,
} from './spod-article/sync'

/** The public folders the module's pictures live in, one per kind (and per writer rule). */
const FOLDERS = {
  subcategoryExamples: 'articles/subcategory-example-images',
  mugVariantExamples: 'articles/mugs/variant-example-images',
  spodListingImages: 'articles/spod/listing-images',
  spodMockups: 'articles/spod/mockups',
  spodSizeCharts: 'articles/spod/size-charts',
} as const

/**
 * The article module: the admin routes of categories, subcategories, mugs, and SPOD articles, the
 * three storefront reads, and two capabilities. `articleCatalog` resolves the article-variant
 * references Cart, Order, Checkout, Production, and the Generator store; `spodCatalogSync`
 * reconciles one destination's backoffice catalog, triggered by Production.
 */
export function createArticleModule({
  db,
  images,
  prices,
  suppliers,
  spod,
}: {
  db: Kysely<Tables>
  images: Pick<ImageModule, 'exampleImages'>
  prices: PriceCatalog
  suppliers: Pick<SupplierModule, 'find'>
  spod: SpodClient
}) {
  const logger = createLogger('article')
  const spodFolders = {
    listingImages: images.exampleImages(FOLDERS.spodListingImages, logger),
    mockups: images.exampleImages(FOLDERS.spodMockups, logger),
    sizeCharts: images.exampleImages(FOLDERS.spodSizeCharts, logger),
  }
  const routes = new Hono()
  routes.route(
    '/',
    categoryRoutes(
      createCategoryService(
        createCategoryRepository(db),
        images.exampleImages(FOLDERS.subcategoryExamples, logger),
      ),
    ),
  )
  routes.route(
    '/',
    mugRoutes(
      createMugService(
        createMugRepository(db, prices),
        images.exampleImages(FOLDERS.mugVariantExamples, logger),
        prices,
        suppliers.find,
      ),
    ),
  )
  routes.route(
    '/',
    spodArticleRoutes(
      createSpodArticleService(
        createSpodArticleRepository(db, prices),
        spodFolders,
        prices,
        suppliers.find,
      ),
    ),
  )
  routes.route('/', storefrontRoutes(createStorefront(db, prices)))
  return {
    routes,
    articleCatalog: createArticleCatalog(db, prices),
    spodCatalogSync: createSpodCatalogSync({
      repository: createSyncRepository(db),
      client: spod,
      folders: spodFolders,
      logger,
    }),
  }
}
