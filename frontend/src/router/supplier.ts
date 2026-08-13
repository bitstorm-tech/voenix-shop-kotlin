import type { RouteRecordRaw } from 'vue-router'
import { supplierGuard } from './guards'

/**
 * Supplier routes - all under /supplier/*
 *
 * Protected by supplierGuard (requires authentication + the supplier role). The area has a single
 * destination today, the job list; `/supplier` therefore redirects to it instead of showing an
 * overview page with nothing on it.
 */
export const supplierRoutes: RouteRecordRaw[] = [
  {
    path: '/supplier',
    component: () => import('@/layouts/SupplierLayout.vue'),
    beforeEnter: supplierGuard,
    meta: {
      requiresAuth: true,
      requiresSupplier: true,
    },
    children: [
      {
        path: '',
        redirect: { name: 'supplier-jobs' },
      },
      {
        path: 'jobs',
        name: 'supplier-jobs',
        component: () => import('@/views/supplier/SupplierJobsView.vue'),
        meta: {
          title: 'Production Jobs',
        },
      },
    ],
  },
]
