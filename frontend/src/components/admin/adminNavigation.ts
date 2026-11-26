import type { Component } from 'vue'
import {
  Bot,
  CircleAlert,
  Database,
  FileText,
  ImageOff,
  KeyRound,
  ShoppingCart,
} from 'lucide-vue-next'

export interface AdminNavLinkItem {
  type: 'link'
  title: string
  to: string
  icon?: Component
  activePatterns?: string[]
  /** Which count of `GET /api/admin/operations/menu-counts` the link shows as a badge. */
  badge?: keyof AdminMenuCounts
}

export interface AdminMenuCounts {
  unshippedOrders: number
  openFindings: number
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
        title: 'Mugs',
        to: '/admin/articles/mugs',
        activePatterns: [
          '/admin/articles/mugs',
          '/admin/articles/mugs/new',
          '/admin/articles/mugs/:id/edit',
        ],
      },
      {
        type: 'link',
        title: 'SPOD articles',
        to: '/admin/articles/spod',
        // A SPOD article is created by a sync run, not by an admin, so there is no `/new` page.
        activePatterns: ['/admin/articles/spod', '/admin/articles/spod/:id/edit'],
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
        title: 'Production Destinations',
        to: '/admin/logistics/destinations',
      },
      {
        type: 'link',
        title: 'SPOD Simulation',
        to: '/admin/logistics/spod-simulation',
      },
      {
        type: 'link',
        title: 'Countries',
        to: '/admin/countries',
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
    badge: 'unshippedOrders',
  },
  {
    type: 'link',
    title: 'Operations',
    to: '/admin/operations',
    icon: CircleAlert,
    badge: 'openFindings',
  },
  {
    type: 'link',
    title: 'Orphaned images',
    to: '/admin/images/orphans',
    icon: ImageOff,
  },
  {
    type: 'link',
    title: 'Access tokens',
    to: '/admin/access-tokens',
    icon: KeyRound,
  },
]

export function getAdminNavLinkActivePatterns(item: AdminNavLinkItem): string[] {
  return item.activePatterns ?? [item.to]
}

export function getAdminNavigationLinks(items: AdminNavItem[]): AdminNavLinkItem[] {
  return items.flatMap((item) => (item.type === 'link' ? [item] : item.children))
}
