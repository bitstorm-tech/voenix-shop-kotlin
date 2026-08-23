import type { RouteRecordRaw } from 'vue-router'
import { adminGuard } from './guards'

/**
 * Admin routes - all under /admin/* path
 * Protected by adminGuard (requires authentication + admin role)
 */
export const adminRoutes: RouteRecordRaw[] = [
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    beforeEnter: adminGuard,
    meta: {
      requiresAuth: true,
      requiresAdmin: true,
    },
    children: [
      {
        path: '',
        name: 'admin-overview',
        component: () => import('@/views/admin/AdminView.vue'),
        meta: {
          title: 'Overview',
        },
      },
      {
        path: 'prompts',
        name: 'admin-prompts',
        component: () => import('@/views/admin/PromptsView.vue'),
        meta: {
          title: 'All Prompts',
        },
      },
      {
        path: 'prompts/categories',
        name: 'admin-prompt-categories',
        component: () => import('@/views/admin/PromptCategoriesView.vue'),
        meta: {
          title: 'Prompt Categories',
        },
      },
      {
        path: 'prompts/slots',
        name: 'admin-prompt-slots',
        component: () => import('@/views/admin/PromptSlotsView.vue'),
        meta: {
          title: 'Prompt Slots',
        },
      },
      {
        path: 'prompts/new',
        name: 'admin-prompt-new',
        component: () => import('@/views/admin/PromptEditView.vue'),
        meta: {
          title: 'New Prompt',
        },
      },
      {
        path: 'prompts/:id/edit',
        name: 'admin-prompt-edit',
        component: () => import('@/views/admin/PromptEditView.vue'),
        meta: {
          title: 'Edit Prompt',
        },
      },
      {
        path: 'articles/mugs',
        name: 'admin-mug-articles',
        component: () => import('@/views/admin/MugArticlesView.vue'),
        meta: {
          title: 'Mugs',
        },
      },
      {
        path: 'articles/tshirts',
        name: 'admin-tshirt-articles',
        component: () => import('@/views/admin/TshirtArticlesView.vue'),
        meta: {
          title: 'T-Shirts',
        },
      },
      {
        path: 'articles/mugs/new',
        name: 'admin-mug-article-new',
        component: () => import('@/views/admin/MugArticleEditView.vue'),
        meta: {
          title: 'New Mug',
        },
      },
      {
        path: 'articles/tshirts/new',
        name: 'admin-tshirt-article-new',
        component: () => import('@/views/admin/TshirtArticleEditView.vue'),
        meta: {
          title: 'New T-Shirt',
        },
      },
      {
        path: 'articles/categories',
        name: 'admin-article-categories',
        component: () => import('@/views/admin/ArticleCategoriesView.vue'),
        meta: {
          title: 'Article Categories',
        },
      },
      {
        path: 'articles/categories/new',
        redirect: { name: 'admin-article-categories' },
      },
      {
        path: 'articles/categories/:id',
        redirect: { name: 'admin-article-categories' },
      },
      {
        path: 'articles/mugs/:id/edit',
        name: 'admin-mug-article-edit',
        component: () => import('@/views/admin/MugArticleEditView.vue'),
        meta: {
          title: 'Edit Mug',
        },
      },
      {
        path: 'articles/tshirts/:id/edit',
        name: 'admin-tshirt-article-edit',
        component: () => import('@/views/admin/TshirtArticleEditView.vue'),
        meta: {
          title: 'Edit T-Shirt',
        },
      },
      {
        path: 'suppliers',
        name: 'admin-suppliers',
        component: () => import('@/views/admin/SuppliersView.vue'),
        meta: {
          title: 'Suppliers',
        },
      },
      {
        path: 'suppliers/new',
        redirect: { name: 'admin-suppliers' },
      },
      {
        path: 'suppliers/:id',
        redirect: { name: 'admin-suppliers' },
      },
      {
        path: 'logistics',
        name: 'admin-logistics',
        component: () => import('@/views/admin/LogisticsView.vue'),
        meta: {
          title: 'Logistics',
        },
      },
      {
        path: 'logistics/destinations',
        name: 'admin-production-destinations',
        component: () => import('@/views/admin/ProductionDestinationsView.vue'),
        meta: {
          title: 'Production Destinations',
        },
      },
      {
        path: 'vat',
        name: 'admin-vat',
        component: () => import('@/views/admin/VatView.vue'),
        meta: {
          title: 'VAT',
        },
      },
      {
        path: 'vat/new',
        redirect: { name: 'admin-vat' },
      },
      {
        path: 'vat/:id',
        redirect: { name: 'admin-vat' },
      },
      {
        path: 'promotions',
        name: 'admin-promotions',
        component: () => import('@/views/admin/PromotionsView.vue'),
        meta: {
          title: 'Promotions',
        },
      },
      {
        path: 'coupons',
        redirect: { name: 'admin-promotions' },
      },
      {
        path: 'orders',
        name: 'admin-orders',
        component: () => import('@/views/admin/OrdersView.vue'),
        meta: {
          title: 'Orders',
        },
      },
      {
        path: 'issues',
        name: 'admin-issues',
        component: () => import('@/views/admin/IssuesView.vue'),
        meta: {
          title: 'Issues',
        },
      },
    ],
  },
]
