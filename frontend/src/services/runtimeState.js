/**
 * Единая трактовка RuntimeState на фронте.
 *
 * Важно: runtime-статус (что происходит сейчас) и desired-state `active`
 * (чего хочет пользователь) — разные вещи. Бэкенд намеренно не гасит desired-state
 * при ошибке, поэтому в UI встречается сочетание active=true + ERROR:
 * это значит «должен работать, но не смог — супервизор повторяет попытку».
 */

export const RUNTIME_ACTIVE = 'ACTIVE'
export const RUNTIME_ACTIVATING = 'ACTIVATING'
export const RUNTIME_INACTIVE = 'INACTIVE'
export const RUNTIME_ERROR = 'ERROR'

export function normalizeState (state) {
  return String(state || '').toUpperCase()
}

export function stateColor (state) {
  switch (normalizeState(state)) {
    case RUNTIME_ACTIVE: return 'positive'
    case RUNTIME_ACTIVATING: return 'warning'
    case RUNTIME_ERROR: return 'negative'
    default: return 'grey-6'
  }
}

export function stateLabel (state) {
  switch (normalizeState(state)) {
    case RUNTIME_ACTIVE: return 'Работает'
    case RUNTIME_ACTIVATING: return 'Запускается'
    case RUNTIME_ERROR: return 'Ошибка'
    case RUNTIME_INACTIVE: return 'Остановлен'
    default: return '—'
  }
}

export function isRuntimeActive (state) {
  return normalizeState(state) === RUNTIME_ACTIVE
}

export function isRuntimeInactive (state) {
  return normalizeState(state) === RUNTIME_INACTIVE
}

/** Человекочитаемые названия типов событий — те же формулировки, что уходят в Telegram. */
const EVENT_TYPE_LABELS = {
  BOT_STARTED: 'Бот запущен',
  BOT_STOPPED: 'Бот остановлен',
  ORDER_PLACED: 'Заявка выставлена',
  ORDER_FILLED: 'Заявка исполнена',
  ORDER_CANCELLED: 'Заявка снята',
  ORDER_REJECTED: 'Заявка отклонена',
  RANGE_EXIT: 'Выход из диапазона',
  RISK_BLOCKED: 'Сработал лимит',
  STREAM_RECONNECTED: 'Переподключение стрима',
  RECONCILED: 'Сверка с биржей',
  ERROR: 'Ошибка',
  HOUSEKEEPING: 'Событие'
}

export function eventTypeLabel (type) {
  return EVENT_TYPE_LABELS[type] || type
}

/** События, которые дублируются в Telegram. Показываем значком, чтобы было видно почему. */
const NOTIFIABLE_TYPES = new Set([
  'BOT_STARTED', 'BOT_STOPPED', 'ORDER_PLACED', 'ORDER_FILLED', 'ORDER_CANCELLED',
  'ORDER_REJECTED', 'RANGE_EXIT', 'RISK_BLOCKED', 'STREAM_RECONNECTED', 'ERROR'
])

export function isNotifiableEvent (type) {
  return NOTIFIABLE_TYPES.has(type)
}

export function formatInstant (instant) {
  if (!instant) return '—'
  const d = new Date(instant)
  if (Number.isNaN(d.getTime())) return instant
  return d.toLocaleString()
}
