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
        path: 'articles',
        name: 'admin-articles',
        component: () => import('@/views/admin/ArticlesView.vue'),
        meta: {
          title: 'All Articles',
        },
      },
      {
        path: 'articles/new',
        name: 'admin-article-new',
        component: () => import('@/views/admin/ArticleEditView.vue'),
        meta: {
          title: 'New Article',
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
        path: 'articles/:id/edit',
        name: 'admin-article-edit',
        component: () => import('@/views/admin/ArticleEditView.vue'),
        meta: {
          title: 'Edit Article',
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
