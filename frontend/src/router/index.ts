import { START_LOCATION, createRouter, createWebHistory } from 'vue-router'
import { adminRoutes } from './admin'
import { authRoutes } from './auth'
import { shopRoutes } from './shop'
import { supplierRoutes } from './supplier'
import EmptyLayout from '@/layouts/EmptyLayout.vue'
import { useAuthStore } from '@/stores/shared/auth'
import { useCampaignStore } from '@/stores/shop/campaign'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    hideFooter?: boolean
    wideContent?: boolean
    /** Marks a campaign landing page; entering the shop on it makes it the header logo's home. */
    campaignLanding?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior() {
    return { top: 0 }
  },
  routes: [
    // Auth routes (login, etc.)
    ...authRoutes,

    // Admin routes (protected, under /admin/*)
    ...adminRoutes,

    // Supplier routes (protected, under /supplier/*)
    ...supplierRoutes,

    // Shop routes (public storefront)
    ...shopRoutes,

    // 404 Not Found
    {
      path: '/:pathMatch(.*)*',
      component: EmptyLayout,
      children: [
        {
          path: '',
          name: 'not-found',
          component: () => import('@/views/NotFoundView.vue'),
          meta: {
            title: 'Page Not Found',
          },
        },
      ],
    },
  ],
})

// Global navigation guard: wait for auth check, then set page title
router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  await authStore.authReadyPromise

  const title = to.meta.title
  if (title) {
    document.title = `${title} | Voenix`
  } else {
    document.title = 'Voenix'
  }
})

// Only the entry page of a visit decides whether the header logo leads to a campaign landing page.
router.afterEach((to, from) => {
  if (from === START_LOCATION) {
    useCampaignStore().startVisit(to.meta.campaignLanding ? to.path : null)
  }
})

export default router
