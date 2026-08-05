<template>
  <div class="q-gutter-md">
    <!-- Проверка безубытка: тот же расчёт, что и на бэке, но до сохранения -->
    <q-banner v-if="economics.error" dense rounded class="bg-red-1 text-red-10">
      <template #avatar>
        <q-icon name="warning" color="negative" />
      </template>
      {{ economics.error }}
    </q-banner>
    <q-banner v-else-if="economics.ready" dense rounded class="bg-green-1 text-green-10">
      <template #avatar>
        <q-icon name="check_circle" color="positive" />
      </template>
      Шаг {{ economics.step }} ({{ economics.stepPct }}% от верхней границы) против
      {{ economics.roundTripPct }}% комиссии за оборот — запас ×{{ economics.ratio }}.
      Прибыль за цикл ≈ {{ economics.netPerCycle }}% .
    </q-banner>

    <div class="row q-col-gutter-md">
      <div class="col-12 col-md-6">
        <q-input
          v-model="model.instrumentUid"
          label="Инструмент (uid)"
          outlined dense :disable="disable"
          hint="UID инструмента у брокера"
        />
      </div>
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.lowerPrice" label="Нижняя граница" type="number" outlined dense :disable="disable" />
      </div>
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.upperPrice" label="Верхняя граница" type="number" outlined dense :disable="disable" />
      </div>

      <div class="col-6 col-md-3">
        <q-input v-model.number="model.levels" label="Уровней" type="number" min="1" outlined dense :disable="disable" />
      </div>
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.lotsPerOrder" label="Лотов в заявке" type="number" min="1" outlined dense :disable="disable" />
      </div>
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.maxActiveOrders" label="Активных заявок" type="number" min="1" outlined dense :disable="disable" />
      </div>
      <div class="col-6 col-md-3">
        <q-select
          v-model="model.onRangeExit"
          :options="rangeExitOptions"
          option-value="value" option-label="label" emit-value map-options
          label="Выход из диапазона" outlined dense :disable="disable"
        />
      </div>
    </div>

    <q-separator />

    <div class="text-subtitle2">Лимиты</div>
    <div class="text-caption text-grey">
      Проверяются перед каждой заявкой и считаются по журналу, поэтому переживают перезапуск.
      Пустое поле означает «без ограничения» — для реальных денег так лучше не оставлять.
    </div>

    <div class="row q-col-gutter-md">
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.maxCapital" label="Капитал, макс." type="number" outlined dense :disable="disable" />
      </div>
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.maxPositionLots" label="Позиция, лотов" type="number" outlined dense :disable="disable" />
      </div>
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.maxOrdersPerDay" label="Заявок в сутки" type="number" outlined dense :disable="disable" />
      </div>
      <div class="col-6 col-md-3">
        <q-input
          v-model.number="model.maxOrdersPerMinute"
          label="Заявок в минуту"
          type="number" outlined dense :disable="disable"
          hint="Защита от разгона"
        />
      </div>
    </div>

    <div class="row items-center q-gutter-lg">
      <q-toggle v-model="model.dryRun" label="Бумажный режим (без реальных заявок)" :disable="disable" />
      <q-toggle v-model="model.enabled" label="Стратегия включена" :disable="disable" />
    </div>

    <q-banner v-if="model.dryRun" dense rounded class="bg-purple-1 text-purple-10">
      <template #avatar>
        <q-icon name="science" color="purple" />
      </template>
      Бумажный режим проверяет логику стратегии, но не доходность: исполнение
      симулируется оптимистично, без очереди заявок и проскальзывания.
    </q-banner>

    <q-separator />

    <!-- Лесенка: одним взглядом видно, где стоят заявки -->
    <div class="row items-center justify-between">
      <div class="text-subtitle2">Уровни сетки</div>
      <q-badge v-if="ladder.length" :label="ladder.length + ' уровней'" color="grey-7" outline />
    </div>

    <q-markup-table v-if="ladder.length" flat dense class="ladder-table">
      <thead>
        <tr>
          <th class="text-left">#</th>
          <th class="text-right">Цена</th>
          <th class="text-left">Заявка</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in ladder" :key="row.level" :class="row.current ? 'bg-blue-1' : ''">
          <td>{{ row.level }}</td>
          <td class="text-right mono">{{ row.price }}</td>
          <td>
            <q-badge v-if="row.order" :color="row.order.side === 'BUY' ? 'green-7' : 'red-7'"
                     :label="(row.order.side === 'BUY' ? 'покупка' : 'продажа') + ' · ' + orderStatusLabel(row.order.status)"
                     outline />
            <span v-else class="text-grey">—</span>
          </td>
        </tr>
      </tbody>
    </q-markup-table>
    <div v-else class="text-grey text-caption">Задайте границы и число уровней</div>
  </div>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '{}' },
  disable: { type: Boolean, default: false },
  /** Ставка комиссии за одну сторону, доля (0.0005 = 0.05%) */
  commissionRate: { type: Number, default: 0.003 },
  /** Шаг цены инструмента; пока неизвестен — считаем достаточно мелким */
  priceIncrement: { type: Number, default: 0.01 },
  openOrders: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const rangeExitOptions = [
  { value: 'STOP_BUYING', label: 'Перестать покупать' },
  { value: 'CANCEL_AND_STOP', label: 'Снять заявки и остановиться' }
]

const defaults = {
  instrumentUid: '',
  lowerPrice: null,
  upperPrice: null,
  levels: 10,
  lotsPerOrder: 1,
  maxActiveOrders: 10,
  onRangeExit: 'STOP_BUYING',
  minStepToCommissionRatio: 1.5,
  maxCapital: null,
  maxPositionLots: null,
  maxOrdersPerDay: null,
  maxOrdersPerMinute: 10,
  dryRun: false,
  enabled: true
}

const model = reactive({ ...defaults })

function loadFrom (json) {
  let parsed = {}
  try {
    parsed = JSON.parse(json || '{}')
  } catch {
    parsed = {}
  }
  Object.assign(model, defaults, parsed)
}

loadFrom(props.modelValue)

watch(() => props.modelValue, (v) => {
  // Не затираем правки пользователя, если строка пришла та же, что мы и отдали.
  if (v !== serialize()) loadFrom(v)
})

function serialize () {
  const out = {}
  for (const [k, v] of Object.entries(model)) {
    if (v === '' || v === null || v === undefined || Number.isNaN(v)) continue
    out[k] = v
  }
  return JSON.stringify(out)
}

watch(model, () => emit('update:modelValue', serialize()), { deep: true })

function roundToIncrement (value, increment) {
  if (!increment || increment <= 0) return value
  return Math.round(value / increment) * increment
}

function fmt (value) {
  const decimals = Math.max(0, Math.ceil(-Math.log10(props.priceIncrement || 0.01)))
  return Number(value).toFixed(decimals)
}

/** Лесенка считается тем же способом, что и на бэке, чтобы UI не расходился с ботом. */
const ladder = computed(() => {
  const low = Number(model.lowerPrice)
  const high = Number(model.upperPrice)
  const levels = Number(model.levels)
  if (!low || !high || high <= low || !levels || levels < 1 || levels > 200) return []

  const rawStep = (high - low) / levels
  const byLevel = new Map()
  for (const o of props.openOrders) {
    if (o.gridLevel !== null && o.gridLevel !== undefined) byLevel.set(o.gridLevel, o)
  }

  const rows = []
  for (let i = 0; i <= levels; i++) {
    rows.push({
      level: i,
      price: fmt(roundToIncrement(low + rawStep * i, props.priceIncrement)),
      order: byLevel.get(i) || null,
      current: false
    })
  }
  return rows.reverse()
})

/**
 * Та же арифметика безубытка, что и в GridValidator: ошибку лучше показать в форме,
 * чем получить отказ при запуске бота.
 */
const economics = computed(() => {
  const low = Number(model.lowerPrice)
  const high = Number(model.upperPrice)
  const levels = Number(model.levels)
  if (!low || !high || high <= low || !levels || levels < 1) {
    return { ready: false, error: '' }
  }

  const prices = ladder.value.map(r => Number(r.price)).sort((a, b) => a - b)
  let step = Infinity
  for (let i = 1; i < prices.length; i++) step = Math.min(step, prices[i] - prices[i - 1])

  if (!Number.isFinite(step) || step <= 0) {
    return { ready: false, error: 'Уровни сетки слиплись: после округления к шагу цены соседние уровни совпадают. Уменьшите число уровней или расширьте диапазон.' }
  }

  const stepPct = step / high
  const roundTrip = (props.commissionRate || 0) * 2
  const required = roundTrip * (Number(model.minStepToCommissionRatio) || 1.5)

  if (stepPct < required) {
    return {
      ready: false,
      error: `Шаг сетки не окупает комиссию: ${(stepPct * 100).toFixed(4)}% против требуемых ${(required * 100).toFixed(4)}% `
        + `(комиссия за оборот ${(roundTrip * 100).toFixed(4)}%). Увеличьте диапазон или уменьшите число уровней.`
    }
  }

  // Худший случай капитала: выкуплены все уровни.
  const worstCase = prices.slice(0, -1).reduce((s, p) => s + p * (Number(model.lotsPerOrder) || 1), 0)
  if (model.maxCapital && worstCase > Number(model.maxCapital)) {
    return {
      ready: false,
      error: `Сетка не помещается в лимит капитала: при полном выкупе потребуется ${worstCase.toFixed(2)} `
        + `при потолке ${Number(model.maxCapital).toFixed(2)}.`
    }
  }

  return {
    ready: true,
    error: '',
    step: step.toFixed(4),
    stepPct: (stepPct * 100).toFixed(4),
    roundTripPct: (roundTrip * 100).toFixed(4),
    ratio: (stepPct / (roundTrip || 1e-12)).toFixed(1),
    netPerCycle: ((stepPct - roundTrip) * 100).toFixed(4)
  }
})

function orderStatusLabel (status) {
  switch (status) {
    case 'PENDING': return 'ожидает сверки'
    case 'NEW': return 'выставлена'
    case 'PARTIALLY_FILLED': return 'частично'
    case 'FILLED': return 'исполнена'
    default: return status
  }
}

defineExpose({ hasError: computed(() => !!economics.value.error) })
</script>

<style scoped>
.ladder-table {
  max-height: 300px;
  overflow: auto;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
}
</style>
