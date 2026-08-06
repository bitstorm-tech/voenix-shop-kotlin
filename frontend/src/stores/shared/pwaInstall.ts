import { defineStore } from 'pinia'
import { computed, shallowRef } from 'vue'

const INSTALL_ACCEPTED_KEY = 'voenix-pwa-install-accepted'

interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

export type PwaInstallResult = 'accepted' | 'dismissed' | 'unavailable'

function readInstallAccepted() {
  if (typeof localStorage === 'undefined') return false

  try {
    return localStorage.getItem(INSTALL_ACCEPTED_KEY) === 'true'
  } catch {
    return false
  }
}

function persistInstallAccepted() {
  if (typeof localStorage === 'undefined') return

  try {
    localStorage.setItem(INSTALL_ACCEPTED_KEY, 'true')
  } catch {
    // Ignore storage failures. Standalone detection still hides the action in installed PWAs.
  }
}

function clearPersistedInstallAccepted() {
  if (typeof localStorage === 'undefined') return

  try {
    localStorage.removeItem(INSTALL_ACCEPTED_KEY)
  } catch {
    // Ignore storage failures. The in-memory flag is still updated.
  }
}

export const usePwaInstallStore = defineStore('pwaInstall', () => {
  const deferredPrompt = shallowRef<BeforeInstallPromptEvent | null>(null)
  const installAccepted = shallowRef(readInstallAccepted())
  const installing = shallowRef(false)
  let cleanupInstallListeners: (() => void) | null = null

  const isIos = computed(() => {
    if (typeof navigator === 'undefined') return false

    const isIphoneOrIpod = /iPhone|iPod/.test(navigator.userAgent)
    const isIpad = /iPad/.test(navigator.userAgent)
    const isIpadOsDesktopMode = navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1

    return isIphoneOrIpod || isIpad || isIpadOsDesktopMode
  })

  const isStandalone = computed(() => {
    if (typeof window === 'undefined' || typeof navigator === 'undefined') return false
    return (
      window.matchMedia('(display-mode: standalone)').matches ||
      (navigator as unknown as { standalone?: boolean }).standalone === true
    )
  })

  const hasNativePrompt = computed(() => deferredPrompt.value !== null)

  const canInstall = computed(() => !isStandalone.value && !installAccepted.value)

  function markInstallAccepted() {
    deferredPrompt.value = null
    installAccepted.value = true
    persistInstallAccepted()
  }

  function markInstallAvailable() {
    installAccepted.value = false
    clearPersistedInstallAccepted()
  }

  function init() {
    if (typeof window === 'undefined' || cleanupInstallListeners) return

    const handleBeforeInstallPrompt = (e: Event) => {
      e.preventDefault()
      markInstallAvailable()
      deferredPrompt.value = e as BeforeInstallPromptEvent
    }

    const handleAppInstalled = () => {
      markInstallAccepted()
    }

    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
    window.addEventListener('appinstalled', handleAppInstalled)

    cleanupInstallListeners = () => {
      window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
      window.removeEventListener('appinstalled', handleAppInstalled)
      cleanupInstallListeners = null
    }
  }

  function dispose() {
    cleanupInstallListeners?.()
  }

  async function installApp(): Promise<PwaInstallResult> {
    const promptEvent = deferredPrompt.value
    if (!promptEvent || installing.value) return 'unavailable'

    installing.value = true

    try {
      await promptEvent.prompt()
      const { outcome } = await promptEvent.userChoice

      if (deferredPrompt.value === promptEvent) {
        deferredPrompt.value = null
      }

      if (outcome === 'accepted') {
        markInstallAccepted()
      }

      return outcome
    } catch {
      if (deferredPrompt.value === promptEvent) {
        deferredPrompt.value = null
      }

      return 'unavailable'
    } finally {
      installing.value = false
    }
  }

  return {
    deferredPrompt,
    isIos,
    isStandalone,
    hasNativePrompt,
    canInstall,
    installing,
    init,
    installApp,
    dispose,
  }
})
