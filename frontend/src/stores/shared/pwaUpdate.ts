import { defineStore } from 'pinia'
import { shallowRef } from 'vue'

const UPDATE_REMINDER_DELAY_MS = 30 * 60 * 1000

export const usePwaUpdateStore = defineStore('pwaUpdate', () => {
  const needsRefresh = shallowRef(false)
  const updateServiceWorker = shallowRef<((reloadPage?: boolean) => void) | null>(null)
  const dismissedUpdatePending = shallowRef(false)
  let reminderTimer: ReturnType<typeof setTimeout> | null = null

  function clearReminderTimer() {
    if (!reminderTimer) return

    clearTimeout(reminderTimer)
    reminderTimer = null
  }

  function showUpdate() {
    clearReminderTimer()
    dismissedUpdatePending.value = false
    needsRefresh.value = true
  }

  function setUpdateAvailable(updateSW: (reloadPage?: boolean) => void) {
    updateServiceWorker.value = updateSW
    showUpdate()
  }

  function applyUpdate() {
    updateServiceWorker.value?.(true)
  }

  function dismissUpdate() {
    needsRefresh.value = false
    dismissedUpdatePending.value = true
    clearReminderTimer()

    reminderTimer = setTimeout(() => {
      if (dismissedUpdatePending.value && updateServiceWorker.value) {
        showUpdate()
      }
    }, UPDATE_REMINDER_DELAY_MS)
  }

  function showDismissedUpdate() {
    if (!dismissedUpdatePending.value || !updateServiceWorker.value) return

    showUpdate()
  }

  return {
    needsRefresh,
    setUpdateAvailable,
    applyUpdate,
    dismissUpdate,
    showDismissedUpdate,
  }
})
