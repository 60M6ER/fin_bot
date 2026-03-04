

import { boot } from 'quasar/wrappers'
import { Notify } from 'quasar'

/**
 * Tiny notification facade to keep messages consistent across the app.
 * Usage:
 *   this.$toast.ok('Saved')
 *   const toast = inject('toast'); toast.err('Oops')
 */

function createToastApi () {
  // Common base options for all notifications
  const base = {
    position: 'top-right',
    timeout: 2500,
    progress: true,
    actions: [{ icon: 'close', color: 'white' }]
  }

  const normalize = (message, opts) => {
    // allow calling with Error
    const msg = message instanceof Error
      ? (message.message || String(message))
      : String(message ?? '')

    return {
      ...base,
      message: msg,
      ...(opts || {})
    }
  }

  return {
    // generic
    show: (message, opts) => Notify.create(normalize(message, opts)),

    // common types
    ok: (message, opts) => Notify.create(normalize(message, { type: 'positive', ...opts })),
    info: (message, opts) => Notify.create(normalize(message, { type: 'info', ...opts })),
    warn: (message, opts) => Notify.create(normalize(message, { type: 'warning', ...opts })),
    err: (message, opts) => Notify.create(normalize(message, { type: 'negative', ...opts })),

    // helpful wrappers
    apiError: (e, fallback = 'Ошибка запроса') => {
      // axios-style errors
      const msg = e?.response?.data?.message
        || e?.response?.data?.error
        || e?.message
        || fallback
      Notify.create(normalize(msg, { type: 'negative' }))
    }
  }
}

export const toast = createToastApi()

export default boot(({ app }) => {
  // Make available everywhere:
  //   - Options API: this.$toast
  //   - Composition API: inject('toast')
  app.config.globalProperties.$toast = toast
  app.provide('toast', toast)
})
