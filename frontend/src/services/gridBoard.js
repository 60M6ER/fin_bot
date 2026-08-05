/**
 * Раскладка активных заявок по уровням сетки.
 *
 * Вынесено из компонента отдельным модулем, потому что здесь живут ровно те правила,
 * которые раньше были нарушены и из-за которых виджет врал:
 *
 *  1. Заявка ищет свою строку по РЕАЛЬНОЙ цене, а не по gridLevel. У продажи
 *     gridLevel — это уровень ПОКУПКИ, который она закрывает, а стоит она на уровень
 *     выше. Раскладка по gridLevel рисовала продажу на строке с чужой ценой.
 *  2. На уровне может висеть НЕСКОЛЬКО заявок. Раньше здесь была Map «уровень →
 *     заявка», и вторая продажа на уровне молча исчезала с экрана.
 *  3. Заявки без места на сетке (ликвидация позиции по лучшему биду, gridLevel = null)
 *     не теряются, а выносятся отдельным списком.
 */

/**
 * @returns {number|null} индекс строки сетки или null, если заявке там не место
 */
export function rowForOrder (order, prices) {
  const price = Number(order.limitPrice)
  if (Number.isFinite(price)) {
    const exact = prices.findIndex(p => Number(p) === price)
    if (exact >= 0) return exact
  }
  // Цена не совпала с лесенкой (сетку только что перестроили, либо это ликвидация) —
  // падаем на уровень, если он вообще известен.
  if (order.gridLevel !== null && order.gridLevel !== undefined && order.gridLevel < prices.length) {
    return order.gridLevel
  }
  return null
}

/**
 * Строки сетки сверху вниз: от верхней цены к нижней.
 *
 * @param snapshot снимок стратегии (ladderPrices, lotsByLevel)
 * @param orders   активные заявки
 */
export function buildGridRows (snapshot, orders) {
  const prices = (snapshot && snapshot.ladderPrices) || []
  if (!prices.length) return []

  const lots = (snapshot && snapshot.lotsByLevel) || []
  const byRow = new Map()

  for (const o of orders) {
    const level = rowForOrder(o, prices)
    if (level === null) continue
    if (!byRow.has(level)) byRow.set(level, [])
    byRow.get(level).push(o)
  }

  return prices
    .map((price, level) => ({
      level,
      price,
      // Верхний уровень продажный: плановых покупок на нём нет, и это не пропуск данных.
      plannedLots: level < lots.length ? lots[level] : null,
      orders: byRow.get(level) || []
    }))
    .reverse()
}

/** Заявки, которым на сетке места нет: закрытие позиции идёт по лучшему биду. */
export function offGridOrders (snapshot, orders) {
  const prices = (snapshot && snapshot.ladderPrices) || []
  return orders.filter(o => rowForOrder(o, prices) === null)
}

/** Сколько лотов ещё не исполнено. */
export function remainingLots (order) {
  return Math.max(0, Number(order.requestedLots || 0) - Number(order.executedLots || 0))
}
