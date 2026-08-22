import { createRouter, createWebHistory } from 'vue-router'
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
    /** Marks a campaign landing page; visiting it makes the page the header logo's home. */
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

// A visitor coming in through a campaign landing page keeps it as their home for this visit.
router.afterEach((to) => {
  if (to.meta.campaignLanding) {
    useCampaignStore().rememberLanding(to.path)
  }
})

export default router
