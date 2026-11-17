import './assets/main.css'

import { createApp, watchEffect } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import i18n from './i18n'
import { initPwa } from './pwa'

const app = createApp(App)

const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(i18n)

app.mount('#app')

// Shop pages take their title from `pageTitles.<route name>` in the reader's language; the
// English-only admin keeps `meta.title`. Search engines and screen readers read `<html lang>`.
watchEffect(() => {
  const route = router.currentRoute.value
  const key = `pageTitles.${String(route.name)}`
  const title = i18n.global.te(key) ? i18n.global.t(key) : route.meta.title
  document.title = title ? `${title} | Voenix` : 'Voenix'
  document.documentElement.lang = i18n.global.locale.value
})

initPwa()
