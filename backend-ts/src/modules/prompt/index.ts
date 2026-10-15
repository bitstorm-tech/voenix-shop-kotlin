import { Hono } from 'hono'
import type { Kysely } from 'kysely'
import type { ImageModule } from '@/modules/image'
import type { PriceCatalog } from '@/modules/pricing'
import { requireAdmin } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'
import { categoryRoutes, subcategoryRoutes } from './category/routes'
import { createPromptCategoryRepository } from './category/repository'
import { createPromptCategoryService } from './category/service'
import { createPromptRepository } from './prompt/repository'
import { promptAdminRoutes, publicPromptRoutes } from './prompt/routes'
import { createPromptCatalog, createPromptService } from './prompt/service'
import { createPromptSlotRepository } from './slot/repository'
import { slotRoutes, slotVariantRoutes } from './slot/routes'
import { createPromptSlotService } from './slot/service'

/** The public folder the example images of prompts are stored in. */
const EXAMPLE_IMAGE_FOLDER = 'prompt-example-images'

/**
 * The prompt module: the admin routes under `/api/admin/prompts` behind one admin protection, the
 * anonymous `GET /api/prompts`, and `promptCatalog`, the capability the generator (`composedText`)
 * and the cart (`findSalesGrossPriceCents`) use. A prompt's price is written through
 * `priceCatalog` inside the prompt's transaction; its example image follows the image module's
 * example-image rule for the `prompt-example-images` folder.
 */
export function createPromptModule({
  db,
  priceCatalog,
  exampleImages,
}: {
  db: Kysely<Tables>
  priceCatalog: PriceCatalog
  exampleImages: ImageModule['exampleImages']
}) {
  const slots = createPromptSlotService(createPromptSlotRepository(db))
  const categories = createPromptCategoryService(createPromptCategoryRepository(db))
  const repository = createPromptRepository(db, priceCatalog)
  const prompts = createPromptService({
    repository,
    priceCatalog,
    exampleImages: exampleImages(EXAMPLE_IMAGE_FOLDER, createLogger('prompt')),
  })

  // The sub-paths go first: `/:id` of the prompts would otherwise answer `/categories`.
  const admin = new Hono()
  admin.use(requireAdmin)
  admin.route('/slots', slotRoutes(slots))
  admin.route('/slot-variants', slotVariantRoutes(slots))
  admin.route('/categories', categoryRoutes(categories))
  admin.route('/subcategories', subcategoryRoutes(categories))
  admin.route('/', promptAdminRoutes(prompts))

  const routes = new Hono()
  routes.route('/', publicPromptRoutes(prompts))
  routes.route('/api/admin/prompts', admin)
  return { routes, promptCatalog: createPromptCatalog({ repository, priceCatalog }) }
}

export type PromptModule = ReturnType<typeof createPromptModule>
export type PromptCatalog = PromptModule['promptCatalog']
