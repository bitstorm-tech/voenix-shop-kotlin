import ShopLayout from '@/layouts/ShopLayout.vue'
import type { RouteRecordRaw, RouteRecordSingleView } from 'vue-router'
import { guestGuard } from './guards'

const shopAuthRoute = (
  path: string,
  name: string,
  component: RouteRecordSingleView['component'],
  title: string,
  guestOnly = false,
): RouteRecordRaw => ({
  path,
  component: ShopLayout,
  children: [
    {
      path: '',
      name,
      component,
      ...(guestOnly ? { beforeEnter: guestGuard } : {}),
      meta: {
        title,
      },
    },
  ],
})

/**
 * Auth routes - login, registration, password reset, etc.
 * Protected by guestGuard (redirects authenticated users)
 */
export const authRoutes: RouteRecordRaw[] = [
  shopAuthRoute('/login', 'login', () => import('@/views/auth/LoginView.vue'), 'Login', true),
  shopAuthRoute(
    '/forgot-password',
    'forgot-password',
    () => import('@/views/auth/ForgotPasswordView.vue'),
    'Forgot Password',
    true,
  ),
  shopAuthRoute(
    '/reset-password',
    'reset-password',
    () => import('@/views/auth/ResetPasswordView.vue'),
    'Reset Password',
    true,
  ),
  shopAuthRoute(
    '/register',
    'register',
    () => import('@/views/auth/RegisterView.vue'),
    'Register',
    true,
  ),
  shopAuthRoute(
    '/confirm-email',
    'confirm-email',
    () => import('@/views/auth/ConfirmEmailView.vue'),
    'Confirm Email',
  ),
  shopAuthRoute(
    '/confirm-change-email',
    'confirm-change-email',
    () => import('@/views/auth/ConfirmChangeEmailView.vue'),
    'Confirm Email Change',
  ),
]
