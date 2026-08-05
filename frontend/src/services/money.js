/**
 * Форматирование денег — общее для всех экранов.
 *
 * Живёт отдельно, потому что баланс и P/L показываются в ТРЁХ списках ботов
 * (BotsPage, IndexPage, ExchangesPage) плюс в карточке бота. Правила фолбэка «—»
 * тонкие (нет цены — это не то же самое, что нет бюджета), и три копии этой
 * логики неизбежно разошлись бы.
 */

/** Через сколько цена из потока считается несвежей. */
const STALE_PRICE_MS = 5 * 60 * 1000

export function formatMoney (value) {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  return Number.isNaN(n) ? String(value) : n.toLocaleString('ru-RU', { maximumFractionDigits: 4 })
}

export function formatMoneyWithCurrency (value, currency) {
  const amount = formatMoney(value)
  return amount === '—' ? amount : `${amount}${currency ? ` ${currency}` : ''}`
}

export function formatFee (value) {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  return Number.isNaN(n) ? String(value) : n.toLocaleString('ru-RU', { maximumFractionDigits: 9 })
}

export function formatFeeWithCurrency (value, currency) {
  const amount = formatFee(value)
  return amount === '—' ? amount : `${amount}${currency ? ` ${currency}` : ''}`
}

export function formatSignedMoney (value, currency) {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  if (Number.isNaN(n)) return String(value)
  const prefix = n > 0 ? '+' : ''
  return `${prefix}${formatMoney(n)}${currency ? ` ${currency}` : ''}`
}

export function moneyTone (value) {
  const n = Number(value)
  if (Number.isNaN(n) || n === 0) return 'text-grey-8'
  return n > 0 ? 'text-positive' : 'text-negative'
}

export function isPriceStale (lastPriceAt) {
  if (!lastPriceAt) return false
  const ts = Date.parse(lastPriceAt)
  return !Number.isNaN(ts) && Date.now() - ts > STALE_PRICE_MS
}

export function formatTime (value) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleString('ru-RU')
}

/**
 * Одна строка баланса и одна строка P/L для списка ботов.
 *
 * Правила, из-за которых это здесь, а не в шаблоне:
 * - нет цены в потоке → рыночную оценку показать нечем, но реализованный P/L есть
 *   всегда: показываем его и честно помечаем, что нереализованная часть не учтена;
 * - бюджет не задан (боты, созданные до появления бюджетов) → баланса нет вовсе,
 *   выдумывать его из лимита капитала нельзя: лимит риска это не деньги.
 */
export function formatValuation (v) {
  const empty = {
    budget: '—',
    budgetHint: 'Нет данных по боту',
    balance: '—',
    balanceHint: 'Нет данных по боту',
    pnl: '—',
    pnlHint: 'Нет данных по боту',
    tone: 'text-grey-8',
    partial: false,
    stale: false
  }
  if (!v) return empty

  const currency = v.currency || ''
  const stale = isPriceStale(v.lastPriceAt)
  const hasBudget = v.budget !== null && v.budget !== undefined
  // Оценка неполна, только если позиция ЕСТЬ, а цены нет. Бот без позиции
  // оценивается точно и без всякой цены — оценивать там нечего.
  const valued = v.totalPnl !== null && v.totalPnl !== undefined

  const budgetHint = hasBudget
    ? (v.profitPolicy === 'COMPOUND'
        ? `Выделено ${formatMoneyWithCurrency(v.budget, currency)}, `
          + `в обороте ${formatMoneyWithCurrency(v.workingBudget, currency)} с учётом прибыли`
        : `Выделено ${formatMoneyWithCurrency(v.budget, currency)}, прибыль выводится`)
    : 'Бюджет боту не задан — задайте его в настройках стратегии'

  let balance = '—'
  let balanceHint
  if (v.equity !== null && v.equity !== undefined) {
    balance = formatMoneyWithCurrency(v.equity, currency)
    balanceHint = `Свободно ${formatMoneyWithCurrency(freeCash(v), currency)}`
      + ` + позиция ${formatMoneyWithCurrency(v.marketValue, currency)}`
  } else if (!hasBudget) {
    balanceHint = 'Бюджет боту не задан — задайте его в настройках стратегии'
  } else {
    balanceHint = 'Есть открытая позиция, но нет актуальной цены для её оценки'
  }

  const pnlValue = valued ? v.totalPnl : v.realizedPnl
  const pnl = formatSignedMoney(pnlValue, currency)
  const pnlHint = valued
    ? `Реализованный ${formatSignedMoney(v.realizedPnl, currency)}, `
      + `нереализованный ${formatSignedMoney(v.unrealizedPnl, currency)}`
    : 'Только реализованный P/L: нет актуальной цены для оценки открытых лотов'

  return {
    budget: hasBudget ? formatMoneyWithCurrency(v.workingBudget, currency) : '—',
    budgetHint,
    balance,
    balanceHint,
    pnl,
    pnlHint,
    tone: moneyTone(pnlValue),
    partial: !valued,
    stale
  }
}

/** Деньги бота, не вложенные в позицию: рабочий бюджет минус себестоимость. */
function freeCash (v) {
  if (v.workingBudget === null || v.workingBudget === undefined) return null
  return Number(v.workingBudget) - Number(v.costBasisOpen || 0)
}

/**
 * Сводка по подключению: что роздано ботам, что они стоят и что лежит без дела.
 * Отрицательный остаток — не ошибка, а признак того, что бюджетов роздано больше,
 * чем есть свободных денег: показываем его явно.
 */
export function formatConnectionValuation (v) {
  if (!v || !v.botCount) {
    return {
      total: v && v.total !== null && v.total !== undefined
        ? formatMoneyWithCurrency(v.total, v.currency || '')
        : '—',
      hint: 'На подключении нет ботов',
      pnl: null,
      tone: 'text-grey-8',
      incomplete: false
    }
  }

  const currency = v.currency || ''
  const parts = [
    `Ботов: ${v.botCount}`,
    `роздано ${formatMoneyWithCurrency(v.allocatedBudget, currency)}`,
    `их баланс ${formatMoneyWithCurrency(v.botsBalance, currency)}`,
    `свободно в портфеле ${formatMoneyWithCurrency(v.unallocatedCash, currency)}`
  ]
  if (v.incomplete) {
    parts.push(`не учтено ботов: ${v.botCount - v.valuedBotCount} (нет бюджета или цены)`)
  }

  return {
    total: formatMoneyWithCurrency(v.total, currency),
    hint: parts.join(' · '),
    pnl: formatSignedMoney(v.botsPnl, currency),
    tone: moneyTone(v.botsPnl),
    incomplete: !!v.incomplete
  }
}
