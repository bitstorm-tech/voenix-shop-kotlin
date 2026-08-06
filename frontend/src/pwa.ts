import { registerSW } from 'virtual:pwa-register'
import { usePwaInstallStore } from './stores/shared/pwaInstall'
import { usePwaUpdateStore } from './stores/shared/pwaUpdate'

const UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000

let initialized = false
let registration: ServiceWorkerRegistration | undefined

function isStandalonePwa() {
  return (
    window.matchMedia('(display-mode: standalone)').matches ||
    (window.navigator as Navigator & { standalone?: boolean }).standalone === true
  )
}

export function initPwa() {
  if (initialized) return
  initialized = true

  const pwaInstallStore = usePwaInstallStore()
  const pwaUpdateStore = usePwaUpdateStore()

  pwaInstallStore.init()

  function checkForUpdate() {
    void registration?.update()
  }

  function checkWhenVisible() {
    if (document.visibilityState !== 'visible') return

    if (isStandalonePwa()) {
      pwaUpdateStore.showDismissedUpdate()
    }

    checkForUpdate()
  }

  function checkWhenFocused() {
    if (isStandalonePwa()) {
      pwaUpdateStore.showDismissedUpdate()
    }

    checkForUpdate()
  }

  const updateSW = registerSW({
    immediate: true,
    onNeedRefresh() {
      if (!isStandalonePwa()) {
        updateSW(false)
        return
      }

      pwaUpdateStore.setUpdateAvailable(updateSW)
    },
    onRegisteredSW(_url, swRegistration) {
      registration = swRegistration
      checkForUpdate()

      if (registration) {
        window.setInterval(checkForUpdate, UPDATE_CHECK_INTERVAL_MS)
      }
    },
  })

  document.addEventListener('visibilitychange', checkWhenVisible)
  window.addEventListener('focus', checkWhenFocused)
  window.addEventListener('online', checkForUpdate)
}
