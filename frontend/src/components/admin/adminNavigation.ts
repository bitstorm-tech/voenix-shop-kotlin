import type { Component } from 'vue'
import { Bot, CircleAlert, Database, FileText, LayoutGrid, ShoppingCart } from 'lucide-vue-next'

export interface AdminNavLinkItem {
  type: 'link'
  title: string
  to: string
  icon?: Component
  activePatterns?: string[]
}

export interface AdminNavGroupItem {
  type: 'group'
  title: string
  icon: Component
  children: AdminNavLinkItem[]
}

export type AdminNavItem = AdminNavLinkItem | AdminNavGroupItem

export const adminNavigationItems: AdminNavItem[] = [
  {
    type: 'link',
    title: 'Overview',
    to: '/admin',
    icon: LayoutGrid,
  },
  {
    type: 'group',
    title: 'Prompts',
    icon: Bot,
    children: [
      {
        type: 'link',
        title: 'All Prompts',
        to: '/admin/prompts',
        activePatterns: ['/admin/prompts', '/admin/prompts/new', '/admin/prompts/:id/edit'],
      },
      {
        type: 'link',
        title: 'Categories',
        to: '/admin/prompts/categories',
      },
      {
        type: 'link',
        title: 'Slots',
        to: '/admin/prompts/slots',
      },
    ],
  },
  {
    type: 'group',
    title: 'Articles',
    icon: FileText,
    children: [
      {
        type: 'link',
        title: 'All Articles',
        to: '/admin/articles',
        activePatterns: ['/admin/articles', '/admin/articles/new', '/admin/articles/:id/edit'],
      },
      {
        type: 'link',
        title: 'Categories',
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
    icon: Database,
    children: [
      {
        type: 'link',
        title: 'Suppliers',
        to: '/admin/suppliers',
      },
      {
        type: 'link',
        title: 'Logistics',
        to: '/admin/logistics',
      },
      {
        type: 'link',
        title: 'VAT',
        to: '/admin/vat',
        activePatterns: ['/admin/vat', '/admin/vat/new', '/admin/vat/:id'],
      },
      {
        type: 'link',
        title: 'Promotions',
        to: '/admin/promotions',
        activePatterns: ['/admin/promotions', '/admin/coupons'],
      },
    ],
  },
  {
    type: 'link',
    title: 'Orders',
    to: '/admin/orders',
    icon: ShoppingCart,
  },
  {
    type: 'link',
    title: 'Issues',
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
