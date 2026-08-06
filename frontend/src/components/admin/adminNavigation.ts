import type { Component } from 'vue'
import { Bot, CircleAlert, Database, FileText, LayoutGrid, ShoppingCart } from 'lucide-vue-next'

export interface AdminNavLinkItem {
  type: 'link'
  title: string
  titleKey?: string
  to: string
  icon?: Component
  activePatterns?: string[]
}

export interface AdminNavGroupItem {
  type: 'group'
  title: string
  titleKey?: string
  icon: Component
  children: AdminNavLinkItem[]
}

export type AdminNavItem = AdminNavLinkItem | AdminNavGroupItem

export const adminNavigationItems: AdminNavItem[] = [
  {
    type: 'link',
    title: 'Overview',
    titleKey: 'admin.navigation.overview',
    to: '/admin',
    icon: LayoutGrid,
  },
  {
    type: 'group',
    title: 'Prompts',
    titleKey: 'admin.navigation.prompts',
    icon: Bot,
    children: [
      {
        type: 'link',
        title: 'All Prompts',
        titleKey: 'admin.navigation.allPrompts',
        to: '/admin/prompts',
        activePatterns: ['/admin/prompts', '/admin/prompts/new', '/admin/prompts/:id/edit'],
      },
      {
        type: 'link',
        title: 'Categories',
        titleKey: 'admin.navigation.categories',
        to: '/admin/prompts/categories',
      },
      {
        type: 'link',
        title: 'Slots',
        titleKey: 'admin.navigation.slots',
        to: '/admin/prompts/slots',
      },
    ],
  },
  {
    type: 'group',
    title: 'Articles',
    titleKey: 'admin.navigation.articles',
    icon: FileText,
    children: [
      {
        type: 'link',
        title: 'All Articles',
        titleKey: 'admin.navigation.allArticles',
        to: '/admin/articles',
        activePatterns: ['/admin/articles', '/admin/articles/new', '/admin/articles/:id/edit'],
      },
      {
        type: 'link',
        title: 'Categories',
        titleKey: 'admin.navigation.categories',
        to: '/admin/articles/categories',
        activePatterns: [
          '/admin/articles/categories',
          '/admin/articles/categories/new',
          '/admin/articles/categories/:id',
        ],
      },
    ],
  },
  {
    type: 'group',
    title: 'Masterdata',
    titleKey: 'admin.navigation.masterdata',
    icon: Database,
    children: [
      {
        type: 'link',
        title: 'Suppliers',
        titleKey: 'admin.navigation.suppliers',
        to: '/admin/suppliers',
      },
      {
        type: 'link',
        title: 'Logistics',
        titleKey: 'admin.navigation.logistics',
        to: '/admin/logistics',
      },
      {
        type: 'link',
        title: 'VAT',
        titleKey: 'admin.navigation.vat',
        to: '/admin/vat',
        activePatterns: ['/admin/vat', '/admin/vat/new', '/admin/vat/:id'],
      },
      {
        type: 'link',
        title: 'Promotions',
        titleKey: 'admin.navigation.promotions',
        to: '/admin/promotions',
        activePatterns: ['/admin/promotions', '/admin/coupons'],
      },
    ],
  },
  {
    type: 'link',
    title: 'Orders',
    titleKey: 'admin.navigation.orders',
    to: '/admin/orders',
    icon: ShoppingCart,
  },
  {
    type: 'link',
    title: 'Issues',
    titleKey: 'admin.navigation.issues',
    to: '/admin/issues',
    icon: CircleAlert,
  },
]

export function getAdminNavLinkActivePatterns(item: AdminNavLinkItem): string[] {
  return item.activePatterns ?? [item.to]
}

export function getAdminNavigationLinks(items: AdminNavItem[]): AdminNavLinkItem[] {
  return items.flatMap((item) => (item.type === 'link' ? [item] : item.children))
}
