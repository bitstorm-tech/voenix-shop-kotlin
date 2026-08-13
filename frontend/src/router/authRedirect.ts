import { useAuthStore } from '@/stores/shared/auth'

/**
 * Where a user goes when nothing else said where to.
 *
 * It is only the *default*: an explicit `?redirect=` on the login page always wins, so this
 * function never overrides where somebody was actually headed.
 *
 * The one role that gets its own landing page is the supplier login, because the shop landing page
 * is not a page it can do anything with — it exists to pack and ship jobs. A user who is *also* an
 * admin keeps the shop landing page: admins navigate the whole application, and a supplier link on
 * such an account would send them somewhere they did not ask to go.
 */
export const getDefaultAuthenticatedRedirect = () => {
  const authStore = useAuthStore()

  if (authStore.isSupplier && !authStore.isAdmin) {
    return '/supplier/jobs'
  }

  return '/'
}
