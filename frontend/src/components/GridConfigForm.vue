<template>
  <div class="q-gutter-md">
    <!-- Проверка безубытка выполняется backend тем же валидатором, что и запуск стратегии. -->
    <q-linear-progress v-if="previewLoading" indeterminate color="primary" />
    <q-banner v-if="preview.error" dense rounded class="bg-red-1 text-red-10">
      <template #avatar>
        <q-icon name="warning" color="negative" />
      </template>
      {{ preview.error }}
    </q-banner>
    <q-banner v-else-if="preview.ready" dense rounded class="bg-green-1 text-green-10">
      <template #avatar>
        <q-icon name="check_circle" color="positive" />
      </template>
      Шаг {{ formatDecimal(preview.effectiveStep, 9) }}
      ({{ formatDecimal(preview.stepPercent) }}% от верхней границы) против
      {{ formatDecimal(preview.roundTripFeePercent) }}% комиссии за оборот
      <template v-if="preview.commissionCoverageRatio != null">
        — запас ×{{ formatDecimal(preview.commissionCoverageRatio, 2) }}
      </template>.
      Прибыль за цикл ≈ {{ formatDecimal(preview.netPerCyclePercent) }}%.
      Полный выкуп: {{ formatDecimal(preview.worstCaseCapital, 2) }} при размере лота {{ preview.lotSize }}.
    </q-banner>
    <q-banner v-if="model.autoRange" dense rounded class="bg-blue-1 text-blue-10">
      <template #avatar>
        <q-icon name="show_chart" color="primary" />
      </template>
      <template v-if="preview.ready">
        Диапазон {{ formatDecimal(preview.lowerPrice, 9) }} — {{ formatDecimal(preview.upperPrice, 9) }}
        по ATR {{ formatDecimal(preview.atr, 9) }} ({{ preview.atrCandlesUsed }} свечей).
      </template>
      <template v-else>
        Границы будут рассчитаны при запуске по ATR и восстановлены после рестарта.
      </template>
    </q-banner>
    <q-banner v-if="model.onRangeExit === 'REPLACE_LOWER'" dense rounded class="bg-red-1 text-red-10">
      <template #avatar>
        <q-icon name="warning" color="negative" />
      </template>
      Пробой вниз принудительно продаёт позицию по лучшему биду и фиксирует убыток.
      При исчерпании любого бюджета бот выключится.
    </q-banner>

    <div class="row q-col-gutter-md">
      <div class="col-12 col-md-8">
        <InstrumentSelect
          v-model="model.instrumentUid"
          :exchange="exchange"
          :disable="disable"
          label="Инструмент"
          hint="Начните вводить тикер или название"
        />
      </div>
      <div class="col-12 col-md-4 flex items-center">
        <q-toggle v-model="model.autoRange" label="Границы по волатильности" :disable="disable" />
      </div>

      <template v-if="!model.autoRange">
        <div class="col-6 col-md-3">
          <q-input v-model.number="model.lowerPrice" label="Нижняя граница" type="number" outlined dense :disable="disable" />
        </div>
        <div class="col-6 col-md-3">
          <q-input v-model.number="model.upperPrice" label="Верхняя граница" type="number" outlined dense :disable="disable" />
        </div>
      </template>
      <template v-else>
        <div class="col-6 col-md-3">
          <q-select
            v-model="model.atrInterval" :options="atrIntervalOptions"
            option-value="value" option-label="label" emit-value map-options
            label="Интервал ATR" outlined dense :disable="disable"
          />
        </div>
        <div class="col-6 col-md-3">
          <q-input v-model.number="model.atrPeriods" label="Свечей ATR" type="number" min="5" outlined dense :disable="disable" />
        </div>
        <div class="col-6 col-md-3">
          <q-input v-model.number="model.atrMultiplier" label="Множитель ATR" type="number" min="0.1" step="0.1" outlined dense :disable="disable" />
        </div>
        <div class="col-6 col-md-3">
          <q-input v-model.number="minHalfWidthPercent" label="Мин. полуширина" type="number" min="0.1" step="0.1" suffix="%" outlined dense :disable="disable" />
        </div>
        <div class="col-6 col-md-3">
          <q-input v-model.number="maxHalfWidthPercent" label="Макс. полуширина" type="number" min="0.1" step="0.1" suffix="%" outlined dense :disable="disable" />
        </div>
        <div class="col-6 col-md-3">
          <q-select
            v-model="model.onUpperBreakout" :options="upperBreakoutOptions"
            option-value="value" option-label="label" emit-value map-options
            label="Пробой верхней границы" outlined dense :disable="disable"
          />
        </div>
        <template v-if="model.onUpperBreakout === 'REPLACE_UPPER' || model.onRangeExit === 'REPLACE_LOWER'">
          <div class="col-6 col-md-3">
            <q-input v-model.number="model.breakoutConfirmSeconds" label="Подтверждение" type="number" min="1" suffix="сек" outlined dense :disable="disable" />
          </div>
          <div class="col-6 col-md-3">
            <q-input v-model.number="breakoutMarginPercent" label="Запас пробоя" type="number" min="0" step="0.1" suffix="%" outlined dense :disable="disable" />
          </div>
          <div class="col-6 col-md-3">
            <q-input v-model.number="model.replaceCooldownSeconds" label="Пауза между заменами" type="number" min="1" suffix="сек" outlined dense :disable="disable" />
          </div>
        </template>
      </template>

      <div class="col-6 col-md-3">
        <q-input v-model.number="model.levels" label="Уровней" type="number" min="1" outlined dense :disable="disable" />
      </div>
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.maxActiveOrders" label="Активных заявок" type="number" min="1" outlined dense :disable="disable" />
      </div>
      <div class="col-6 col-md-3">
        <q-select
          v-model="model.onRangeExit"
          :options="availableRangeExitOptions"
          option-value="value" option-label="label" emit-value map-options
          label="Выход из диапазона" outlined dense :disable="disable"
        />
      </div>
      <template v-if="model.onRangeExit === 'REPLACE_LOWER'">
        <div class="col-6 col-md-3">
          <q-input
            v-model.number="model.maxDownwardReplacements"
            label="Перестановок вниз, макс."
            type="number" min="1" outlined dense :disable="disable"
          />
        </div>
        <div class="col-6 col-md-3">
          <q-input
            v-model.number="model.maxRealizedLoss"
            label="Убыток перестановок, макс."
            type="number" min="0.01" step="0.01" outlined dense :disable="disable"
          />
        </div>
      </template>
    </div>

    <q-separator />

    <div class="text-subtitle2">Бюджет и размер заявки</div>
    <div class="text-caption text-grey">
      Бюджет — конкретная сумма, а не доля портфеля: иначе изменившийся портфель
      молча передвинул бы деньги бота при первой же перестройке сетки.
      Размер заявки подбирается так, чтобы полный выкуп всех уровней уложился в бюджет.
    </div>

    <div class="row q-col-gutter-md">
      <div class="col-12 col-md-4">
        <q-select
          v-model="model.sizingMode"
          :options="sizingModeOptions"
          option-value="value" option-label="label" emit-value map-options
          label="Размер заявки" outlined dense :disable="disable"
        />
      </div>

      <div v-if="model.sizingMode === 'FIXED_LOTS'" class="col-6 col-md-3">
        <q-input
          v-model.number="model.lotsPerOrder"
          label="Лотов в заявке" type="number" min="1"
          outlined dense :disable="disable"
        />
      </div>

      <template v-else>
        <div class="col-6 col-md-3">
          <q-input
            v-model.number="model.budget"
            label="Бюджет бота" type="number" min="0" step="0.01"
            outlined dense :disable="disable"
            :suffix="preview.cashCurrency || ''"
          />
        </div>
        <div class="col-6 col-md-2">
          <q-input
            v-model.number="budgetPercent"
            label="% от свободных"
            type="number" min="0" max="100" step="1" suffix="%"
            outlined dense :disable="disable || preview.availableCash === null"
          />
        </div>
        <div class="col-6 col-md-3 flex items-center">
          <q-btn
            outline color="primary" label="Подставить" no-caps
            :disable="disable || preview.availableCash === null || !budgetPercent"
            @click="applyBudgetPercent"
          >
            <q-tooltip v-if="preview.availableCash === null">
              Свободные деньги счёта неизвестны: нужно активное подключение
            </q-tooltip>
            <q-tooltip v-else>
              Свободно {{ preview.availableCash }} {{ preview.cashCurrency || '' }} —
              подставит конкретную сумму, процент не сохраняется
            </q-tooltip>
          </q-btn>
        </div>
        <div class="col-12 col-md-4">
          <q-select
            v-model="model.profitPolicy"
            :options="profitPolicyOptions"
            option-value="value" option-label="label" emit-value map-options
            label="Прибыль" outlined dense :disable="disable"
            :hint="model.profitPolicy === 'COMPOUND'
              ? 'Рабочий бюджет = бюджет + реализованный P/L'
              : 'Рабочий бюджет всегда равен бюджету, прибыль показывается отдельно'"
          />
        </div>
      </template>
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
          :hint="model.onRangeExit === 'REPLACE_LOWER'
            ? `Для перестановок рекомендуется не меньше ${4 * Number(model.levels || 1)}`
            : 'Защита от разгона'"
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

    <q-banner v-if="sizingSummary" dense rounded class="bg-blue-1 text-blue-10">
      <template #avatar>
        <q-icon name="calculate" color="primary" />
      </template>
      {{ sizingSummary }}
    </q-banner>

    <q-markup-table v-if="ladder.length" flat dense class="ladder-table">
      <thead>
        <tr>
          <th class="text-left">#</th>
          <th class="text-right">Цена</th>
          <th class="text-right">Лотов</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in ladder" :key="row.level" :class="row.current ? 'bg-blue-1' : ''">
          <td>{{ row.level }}</td>
          <td class="text-right mono">{{ row.price }}</td>
          <td class="text-right mono">
            <span v-if="row.lots !== null">{{ row.lots }}</span>
            <span v-else class="text-grey">—</span>
          </td>
        </tr>
      </tbody>
    </q-markup-table>
    <div v-else class="text-grey text-caption">
      {{ previewLoading
        ? 'Проверяю сетку'
        : model.autoRange
          ? 'Для предпросмотра нужны активное подключение и рыночные данные'
          : 'Задайте инструмент, подключение и границы' }}
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { apiClient, getErrorMessage } from 'src/services/apiClient'
import InstrumentSelect from 'components/InstrumentSelect.vue'

const props = defineProps({
  modelValue: { type: String, default: '{}' },
  disable: { type: Boolean, default: false },
  exchange: { type: String, default: null },
  connectionId: { type: String, default: null },
})
const emit = defineEmits(['update:modelValue'])

const rangeExitOptions = [
  { value: 'STOP_BUYING', label: 'Перестать покупать' },
  { value: 'CANCEL_AND_STOP', label: 'Снять заявки и остановиться' },
  { value: 'REPLACE_LOWER', label: 'Закрыть позицию и переставить вниз' }
]

const atrIntervalOptions = [
  { value: 'M5', label: '5 минут' },
  { value: 'M15', label: '15 минут' },
  { value: 'H1', label: '1 час' },
  { value: 'D1', label: '1 день' }
]

const upperBreakoutOptions = [
  { value: 'NOTHING', label: 'Ничего не делать' },
  { value: 'REPLACE_UPPER', label: 'Переставить диапазон вверх' }
]

const sizingModeOptions = [
  { value: 'FIXED_LOTS', label: 'Фиксировано лотов' },
  { value: 'UNIFORM', label: 'Один размер на все уровни (от бюджета)' },
  { value: 'PER_LEVEL', label: 'Поровну денег на уровень (от бюджета)' }
]

const profitPolicyOptions = [
  { value: 'WITHDRAW', label: 'Выводится (бюджет фиксирован)' },
  { value: 'COMPOUND', label: 'Реинвестируется' }
]

const defaults = {
  instrumentUid: '',
  autoRange: false,
  lowerPrice: null,
  upperPrice: null,
  levels: 10,
  lotsPerOrder: 1,
  // FIXED_LOTS по умолчанию: создание бота «по-старому» не меняется,
  // на бюджет переходят осознанно.
  sizingMode: 'FIXED_LOTS',
  budget: null,
  profitPolicy: 'WITHDRAW',
  maxActiveOrders: 10,
  onRangeExit: 'STOP_BUYING',
  atrInterval: 'H1',
  atrPeriods: 24,
  atrMultiplier: 2,
  minHalfWidthPct: 0.01,
  maxHalfWidthPct: 0.15,
  onUpperBreakout: 'NOTHING',
  breakoutConfirmSeconds: 300,
  breakoutMarginPct: 0.002,
  replaceCooldownSeconds: 1200,
  maxDownwardReplacements: 0,
  maxRealizedLoss: null,
  minStepToCommissionRatio: 1.5,
  maxCapital: null,
  maxPositionLots: null,
  maxOrdersPerDay: null,
  maxOrdersPerMinute: 10,
  dryRun: false,
  enabled: true
}

const model = reactive({ ...defaults })

const availableRangeExitOptions = computed(() => model.autoRange
  ? rangeExitOptions
  : rangeExitOptions.filter(option => option.value !== 'REPLACE_LOWER'))

/**
 * Процент живёт только в форме и никогда не уходит на backend.
 *
 * Кнопка «Подставить» превращает его в конкретную сумму — именно она сохраняется.
 * Хранить процент означало бы, что при следующей перестройке сетки бот пересчитает
 * бюджет от изменившегося портфеля, то есть заберёт деньги без команды.
 */
const budgetPercent = ref(null)

/** Что именно получилось из бюджета — самое полезное подтверждение перед запуском. */
const sizingSummary = computed(() => {
  const p = preview.value
  if (!p.ready || !p.sizingMode || p.sizingMode === 'FIXED_LOTS') return ''

  const lots = p.lotsByLevel || []
  if (!lots.length) return ''
  const min = Math.min(...lots)
  const max = Math.max(...lots)
  const size = min === max ? `по ${min} лот(ов) на уровень` : `от ${min} до ${max} лот(ов) по уровням`
  const cur = p.cashCurrency ? ` ${p.cashCurrency}` : ''

  return `Размер заявки: ${size}. Задействовано ${formatDecimal(p.worstCaseCapital, 2)}${cur}`
    + ` из бюджета ${formatDecimal(p.workingBudget, 2)}${cur}`
    + `, остаток ${formatDecimal(p.budgetLeftover, 2)}${cur}.`
})

function applyBudgetPercent () {
  const cash = Number(preview.value.availableCash)
  const pct = Number(budgetPercent.value)
  if (!Number.isFinite(cash) || !Number.isFinite(pct) || pct <= 0) return
  model.budget = Math.round(cash * pct) / 100
}

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

// serialize() отбрасывает null, поэтому лишний ключ в JSON не остаётся —
// достаточно проставить осмысленное значение для того режима, в который перешли.
watch(() => model.sizingMode, (mode) => {
  if (mode === 'FIXED_LOTS') {
    if (model.lotsPerOrder == null) model.lotsPerOrder = 1
  } else {
    model.lotsPerOrder = null
    if (model.budget == null && preview.value.availableCash != null) {
      model.budget = Number(preview.value.availableCash)
    }
  }
})

watch(() => model.autoRange, (enabled, previous) => {
  if (enabled && !previous) {
    model.lowerPrice = null
    model.upperPrice = null
  }
  if (!enabled) {
    model.onUpperBreakout = 'NOTHING'
    if (model.onRangeExit === 'REPLACE_LOWER') model.onRangeExit = 'STOP_BUYING'
  }
})

watch(() => model.onRangeExit, action => {
  if (action === 'REPLACE_LOWER' && Number(model.maxDownwardReplacements || 0) <= 0) {
    model.maxDownwardReplacements = 1
  }
})

const minHalfWidthPercent = computed({
  get: () => Number(model.minHalfWidthPct || 0) * 100,
  set: value => { model.minHalfWidthPct = Number(value) / 100 }
})

const maxHalfWidthPercent = computed({
  get: () => Number(model.maxHalfWidthPct || 0) * 100,
  set: value => { model.maxHalfWidthPct = Number(value) / 100 }
})

const breakoutMarginPercent = computed({
  get: () => Number(model.breakoutMarginPct || 0) * 100,
  set: value => { model.breakoutMarginPct = Number(value) / 100 }
})

const preview = ref({ ready: false, error: '', ladderPrices: [], lotsByLevel: [], availableCash: null, cashCurrency: null })
const previewLoading = ref(false)
let previewTimer = null
let previewVersion = 0

function canPreview () {
  if (!props.connectionId || !String(model.instrumentUid || '').trim()) return false
  if (!model.autoRange && (model.lowerPrice == null || model.upperPrice == null)) return false
  return true
}

function clearPreview () {
  preview.value = { ready: false, error: '', ladderPrices: [], lotsByLevel: [], availableCash: null, cashCurrency: null }
  previewLoading.value = false
}

function schedulePreview () {
  if (previewTimer) clearTimeout(previewTimer)
  const version = ++previewVersion
  clearPreview()
  if (!canPreview()) return

  previewLoading.value = true
  previewTimer = setTimeout(() => loadPreview(version), 350)
}

async function loadPreview (version) {
  try {
    const result = await apiClient.post('/api/v1/bots/grid-preview', {
      exchangeConnectionId: props.connectionId,
      strategyConfig: serialize()
    })
    if (version !== previewVersion) return
    preview.value = result || { ready: false, error: 'Backend не вернул результат проверки', ladderPrices: [] }
  } catch (e) {
    if (version !== previewVersion) return
    preview.value = {
      ready: false,
      error: getErrorMessage(e, 'Не удалось проверить сетку'),
      ladderPrices: [],
      lotsByLevel: [],
      availableCash: null,
      cashCurrency: null
    }
  } finally {
    if (version === previewVersion) previewLoading.value = false
  }
}

watch(() => [props.connectionId, serialize()], schedulePreview, { immediate: true })

onBeforeUnmount(() => {
  if (previewTimer) clearTimeout(previewTimer)
  previewVersion++
})

/** Ордера накладываются на рассчитанные backend уровни только для отображения. */
const ladder = computed(() => {
  return (preview.value.ladderPrices || [])
    .map((price, level) => ({
      level,
      price: formatDecimal(price, 9),
      lots: (preview.value.lotsByLevel || [])[level] ?? null,
      current: false
    }))
    .reverse()
})

function formatDecimal (value, maximumFractionDigits = 4) {
  if (value === null || value === undefined) return '—'
  const number = Number(value)
  return Number.isNaN(number)
    ? String(value)
    : number.toLocaleString('ru-RU', { maximumFractionDigits })
}

defineExpose({ hasError: computed(() => !!preview.value.error) })
</script>

<style scoped>
/*
 * Без max-height: раньше нижний уровень уезжал под обрез без единого
 * признака, что таблица прокручивается, и выглядел как пропавший.
 */
.ladder-table {
  max-height: 60vh;
  overflow: auto;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
}
</style>
