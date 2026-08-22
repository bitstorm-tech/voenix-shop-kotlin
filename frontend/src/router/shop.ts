import type { RouteRecordRaw } from 'vue-router'
import ShopLayout from '@/layouts/ShopLayout.vue'
import { MAGIC_COINS_ROUTE } from '@/lib/magicCoins'
import { authGuard } from './guards'

/**
 * Shop routes - public storefront routes
 * No authentication required for most shop routes
 */
export const shopRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    component: ShopLayout,
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/shop/HomeView.vue'),
        meta: {
          title: 'Home',
        },
      },
      /**
       * Niche campaign landing page (royal dog portraits). Social ads link here; the page
       * funnels into the wizard upload-first with the campaign's prompt preselected.
       */
      {
        path: 'royal-dog',
        name: 'royal-dog',
        component: () => import('@/views/shop/RoyalDogView.vue'),
        meta: {
          title: 'Royal Dog',
          campaignLanding: true,
        },
      },
      {
        path: 'about',
        name: 'about',
        component: () => import('@/views/shop/AboutView.vue'),
        meta: {
          title: 'About',
        },
      },
      {
        path: 'faq',
        name: 'faq',
        component: () => import('@/views/shop/FaqView.vue'),
        meta: {
          title: 'FAQ',
        },
      },
      {
        path: 'contact',
        name: 'contact',
        component: () => import('@/views/shop/ContactView.vue'),
        meta: {
          title: 'Contact',
        },
      },
      {
        path: 'support',
        name: 'support',
        component: () => import('@/views/shop/SupportView.vue'),
        meta: {
          title: 'Support',
        },
      },
      {
        path: 'privacy',
        name: 'privacy',
        component: () => import('@/views/shop/PrivacyView.vue'),
        meta: {
          title: 'Privacy Policy',
        },
      },
      {
        path: 'terms',
        name: 'terms',
        component: () => import('@/views/shop/TermsView.vue'),
        meta: {
          title: 'Terms & Conditions',
        },
      },
      {
        path: 'shipping',
        name: 'shipping',
        component: () => import('@/views/shop/ShippingView.vue'),
        meta: {
          title: 'Shipping Information',
        },
      },
      {
        path: 'returns',
        name: 'returns',
        component: () => import('@/views/shop/ReturnsView.vue'),
        meta: {
          title: 'Returns & Refunds',
        },
      },
      {
        path: 'payment',
        name: 'payment',
        component: () => import('@/views/shop/PaymentView.vue'),
        meta: {
          title: 'Payment',
        },
      },
      {
        path: 'imprint',
        name: 'imprint',
        component: () => import('@/views/shop/ImprintView.vue'),
        meta: {
          title: 'Imprint',
        },
      },
      /**
       * The one product listing of the shop. It shows every article type in a single grid and
       * narrows through its query: `category`, `subcategory`, and the optional `type` (`MUG` or
       * `TSHIRT`). The former `/mugs` route is gone without a redirect — nothing is in production
       * yet, so there is no bookmark to keep alive.
       */
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/shop/ProductOverviewView.vue'),
        meta: {
          title: 'Products',
        },
      },
      {
        path: 'wizard',
        name: 'wizard',
        component: () => import('@/views/shop/WizardView.vue'),
        meta: {
          title: 'Wizard',
          hideFooter: true,
        },
      },
      {
        path: 'editor/:draftId?',
        name: 'editor',
        component: () => import('@/views/shop/EditorView.vue'),
        meta: {
          title: 'Editor',
          hideFooter: true,
          wideContent: true,
        },
      },
      {
        path: MAGIC_COINS_ROUTE.slice(1),
        name: 'magic-coins',
        component: () => import('@/views/shop/MagicCoinsView.vue'),
        meta: {
          title: 'Magic Coins',
        },
      },
      {
        path: 'cart',
        name: 'cart',
        component: () => import('@/views/shop/CartView.vue'),
        meta: {
          title: 'Cart',
        },
      },
      {
        path: 'checkout',
        name: 'checkout',
        component: () => import('@/views/shop/CheckoutView.vue'),
        meta: {
          title: 'Checkout',
        },
      },
      {
        path: 'order-confirmation',
        name: 'order-confirmation',
        component: () => import('@/views/shop/OrderConfirmationView.vue'),
        meta: {
          title: 'Order Confirmation',
        },
      },
      /**
       * The permanent link from the confirmation mail. It carries the order's access token in the
       * path and is deliberately guard-free: whoever holds the token reads that one order, with or
       * without an account, and an anonymous visitor must never be bounced to the login page
       * (issue #110).
       */
      {
        path: 'order/:token',
        name: 'order-link',
        component: () => import('@/views/shop/OrderLinkView.vue'),
        meta: {
          title: 'Order',
        },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/shop/ProfileView.vue'),
        beforeEnter: authGuard,
        meta: {
          title: 'Profile',
        },
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/shop/OrderView.vue'),
        beforeEnter: authGuard,
        meta: {
          title: 'Orders',
        },
      },
    ],
  },
]
