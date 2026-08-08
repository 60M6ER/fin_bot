<template>
  <div class="grid-state">
    <!-- Слева: что реально висит на бирже прямо сейчас -->
    <div class="grid-state__orders">
      <div class="grid-state__title">
        Активные заявки
        <q-badge :label="gridOrders.length" color="grey-7" outline class="q-ml-xs" />
      </div>

      <div v-if="!gridOrders.length" class="text-caption text-grey q-mt-sm">
        Заявок на бирже нет
      </div>

      <div
        v-for="o in sortedOrders"
        :key="o.id"
        class="grid-state__order"
        :class="o.side === 'BUY' ? 'text-green-8' : 'text-red-8'"
      >
        <span class="grid-state__side">{{ o.side === 'BUY' ? '▼' : '▲' }}</span>
        <span class="grid-state__price mono">{{ formatMoney(o.limitPrice) }}</span>
        <span class="grid-state__lots mono">{{ remaining(o) }}</span>
        <q-tooltip anchor="top middle" self="bottom middle">
          {{ o.side === 'BUY' ? 'Покупка' : 'Продажа' }} ·
          {{ orderStatusLabel(o.status) }} ·
          {{ formatQuantity(o.executedQuantity) }}/{{ formatQuantity(o.requestedQuantity) }}
          <template v-if="o.gridLevel !== null && o.gridLevel !== undefined">
            <br>{{ o.side === 'BUY'
              ? `Уровень ${o.gridLevel}`
              : `Закрывает уровень ${o.gridLevel}` }}
          </template>
          <template v-else><br>Вне сетки (закрытие позиции)</template>
        </q-tooltip>
      </div>
    </div>

    <!-- Справа: сама сетка. Показывается целиком, без обрезания нижнего уровня. -->
    <div class="grid-state__ladder">
      <div class="grid-state__title">Сетка</div>

      <div v-if="!rows.length" class="text-caption text-grey q-mt-sm">
        Диапазон ещё не рассчитан
      </div>

      <div
        v-for="row in rows"
        :key="row.level"
        class="grid-state__cell"
        :class="{ 'grid-state__cell--busy': row.orders.length }"
      >
        <span class="grid-state__level">{{ row.level }}</span>
        <span class="grid-state__price mono">{{ formatMoney(row.price) }}</span>

        <span v-if="row.plannedQuantity !== null" class="grid-state__planned mono">
          {{ formatQuantity(row.plannedQuantity) }}
        </span>
        <span v-else class="grid-state__planned text-grey-5">
          только продажа
          <q-tooltip>
            Верхний уровень: покупать на нём некуда — встречной продажи выше не существует.
          </q-tooltip>
        </span>

        <span class="grid-state__badges">
          <q-badge
            v-for="o in row.orders"
            :key="o.id"
            :color="o.side === 'BUY' ? 'green-7' : 'red-7'"
            :label="(o.side === 'BUY' ? '▼' : '▲') + ' ' + remaining(o)"
            outline
            class="q-mr-xs"
          >
            <q-tooltip>
              {{ o.side === 'BUY' ? 'Покупка' : 'Продажа' }} по
              {{ formatMoney(o.limitPrice) }} · {{ orderStatusLabel(o.status) }}
              <template v-if="o.side === 'SELL' && o.gridLevel !== null">
                <br>Закрывает уровень {{ o.gridLevel }}
              </template>
            </q-tooltip>
          </q-badge>
        </span>
      </div>

      <!--
        Только когда лесенка есть. Без неё «вне сетки» оказались бы вообще все
        заявки, и подпись врала бы: они не вне сетки, это сетка ещё не построена.
      -->
      <div v-if="rows.length && offGrid.length" class="text-caption text-orange-9 q-mt-sm">
        Вне сетки: {{ offGrid.length }} — закрытие позиции по рынку
      </div>
    </div>

    <!--
      Пыль стоит отдельно от сетки намеренно: она не занимает уровня, переживает
      перестановку диапазона и продаётся по своей себестоимости, а не по лесенке.
      В общем столбце заявок она читалась бы как заявка сетки, которой там нет.
    -->
    <div v-if="dustOrders.length" class="grid-state__dust">
      <div class="grid-state__title">Пыль</div>

      <div
        v-for="o in dustOrders"
        :key="o.id"
        class="grid-state__order text-purple-8"
      >
        <span class="grid-state__side">▲</span>
        <span class="grid-state__price mono">{{ formatMoney(o.limitPrice) }}</span>
        <span class="grid-state__lots mono">{{ remaining(o) }}</span>
        <q-tooltip anchor="top middle" self="bottom middle">
          Продажа накопленной пыли · {{ orderStatusLabel(o.status) }}
          <br>Непродаваемые по отдельности хвосты закрытых циклов, собранные вместе
        </q-tooltip>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { formatMoney } from 'src/services/money'
import { buildGridRows, offGridOrders, remainingQuantity, formatQuantity } from 'src/services/gridBoard'

const props = defineProps({
  snapshot: { type: Object, default: null },
  orders: { type: Array, default: () => [] }
})

const STATUS_LABELS = {
  PENDING: 'отправляется',
  NEW: 'на бирже',
  PARTIALLY_FILLED: 'частично исполнена',
  UNKNOWN: 'статус неизвестен'
}

function orderStatusLabel (status) {
  return STATUS_LABELS[status] || status
}

const remaining = remainingQuantity

/** Дешевле сверху вниз — читается как ценовая шкала. */
const sortedOrders = computed(() =>
  [...gridOrders.value].sort((a, b) => Number(b.limitPrice || 0) - Number(a.limitPrice || 0)))

/** Пыль живёт своим столбцом: в сетке ей места нет, и в «вне сетки» она не ошибка. */
const dustOrders = computed(() => props.orders.filter(o => o.purpose === 'DUST'))

const gridOrders = computed(() => props.orders.filter(o => o.purpose !== 'DUST'))

const rows = computed(() => buildGridRows(props.snapshot, gridOrders.value))

const offGrid = computed(() => offGridOrders(props.snapshot, gridOrders.value))
</script>

<style scoped>
.grid-state {
  display: grid;
  grid-template-columns: minmax(150px, 200px) 1fr auto;
  gap: 16px;
}

@media (max-width: 700px) {
  .grid-state {
    grid-template-columns: 1fr;
  }
}

.grid-state__title {
  font-size: 12px;
  color: #757575;
  margin-bottom: 6px;
}

.grid-state__orders {
  border-right: 1px solid var(--q-color-grey-4, #e0e0e0);
  padding-right: 12px;
}

@media (max-width: 700px) {
  .grid-state__orders {
    border-right: none;
    border-bottom: 1px solid var(--q-color-grey-4, #e0e0e0);
    padding-right: 0;
    padding-bottom: 12px;
  }
}

.grid-state__order {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 2px 0;
  font-size: 12px;
}

.grid-state__side {
  width: 10px;
}

.grid-state__price {
  min-width: 62px;
}

.grid-state__lots {
  color: #757575;
}

/*
 * Сетка помещается целиком. Раньше здесь стоял max-height, и самый нижний уровень
 * уезжал под обрез без единого признака, что список прокручивается.
 */
.grid-state__cell {
  display: grid;
  grid-template-columns: 28px 72px 90px 1fr;
  align-items: center;
  gap: 8px;
  padding: 2px 4px;
  font-size: 12px;
  border-radius: 4px;
}

.grid-state__cell--busy {
  background: rgba(25, 118, 210, 0.08);
}

.grid-state__level {
  color: #9e9e9e;
  text-align: right;
}

.grid-state__planned {
  color: #757575;
}

.grid-state__badges {
  min-height: 18px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
}
</style>
