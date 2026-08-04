<template>
  <q-page class="q-pa-md page-root">
    <div class="row items-center justify-between q-mb-md">
      <div class="text-h5">Боты</div>
      <q-btn color="primary" icon="add" label="Создать" @click="openCreate()" />
    </div>

    <div class="main-row">
      <!-- LEFT: list -->
      <div class="left-pane">
        <div class="pane-header">
          <div class="text-subtitle1">Список</div>
          <q-btn dense flat round icon="refresh" :loading="listLoading" @click="loadList" />
        </div>

        <q-separator />

        <q-scroll-area class="pane-scroll">
          <q-inner-loading :showing="listLoading">
            <q-spinner />
          </q-inner-loading>

          <q-list separator padding>
            <q-item
              v-for="item in list"
              :key="item.id"
              clickable
              :active="selectedId === item.id"
              active-class="bg-grey-2"
              @click="select(item.id)"
            >
              <q-item-section>
                <q-item-label class="text-weight-medium">{{ item.name }}</q-item-label>
                <q-item-label caption>
                  {{ item.strategyType }} · {{ item.exchangeConnectionName || 'без подключения' }}
                </q-item-label>
              </q-item-section>

              <q-item-section side>
                <div class="row items-center q-gutter-xs">
                  <q-icon v-if="needsAttention(item)" name="sync_problem" color="warning" size="xs">
                    <q-tooltip>Должен работать, но не запущен</q-tooltip>
                  </q-icon>
                  <q-badge
                    :color="stateColor(item.runtime && item.runtime.state)"
                    :label="stateLabel(item.runtime && item.runtime.state)"
                    outline
                  />
                </div>
              </q-item-section>
            </q-item>

            <q-item v-if="!listLoading && list.length === 0">
              <q-item-section>
                <q-item-label class="text-grey">Ботов пока нет</q-item-label>
              </q-item-section>
            </q-item>
          </q-list>
        </q-scroll-area>
      </div>

      <!-- RIGHT: details -->
      <div class="right-pane">
        <div class="pane-header">
          <div class="text-subtitle1">Детали</div>

          <div class="row items-center q-gutter-sm">
            <q-btn
              v-if="detail"
              dense
              :color="detail.active ? 'negative' : 'primary'"
              :label="detail.active ? 'Остановить' : 'Запустить'"
              :loading="actionLoading"
              :disable="detailLoading || actionLoading"
              @click="doActivateDeactivate"
            />

            <q-btn
              dense
              flat
              icon="refresh"
              :disable="!detail || detailLoading"
              :loading="runtimeLoading"
              @click="loadDetail(detail && detail.id)"
            />

            <q-btn
              dense
              flat
              icon="delete"
              color="negative"
              :disable="!detail || detailLoading || actionLoading"
              @click="confirmDelete = true"
            />
          </div>
        </div>

        <q-separator />

        <q-scroll-area class="pane-scroll">
          <q-inner-loading :showing="detailLoading">
            <q-spinner />
          </q-inner-loading>

          <div v-if="!selectedId" class="q-pa-md text-grey">
            Выбери бота слева.
          </div>

          <div v-else-if="detail" class="q-pa-md">
            <!-- Почему бот не работает: чаще всего дело в подключении -->
            <q-banner v-if="blockedByConnection" dense class="bg-orange-1 text-orange-10 q-mb-md" rounded>
              <template #avatar>
                <q-icon name="link_off" color="orange-9" />
              </template>
              Подключение «{{ detail.exchangeConnectionName }}» не активно
              ({{ stateLabel(detail.exchangeConnectionState) }}).
              Бот не запустится, пока оно не поднимется.
              <template #action>
                <q-btn flat dense label="К биржам" to="/exchanges" />
              </template>
            </q-banner>

            <q-banner v-else-if="needsAttention(detail)" dense class="bg-orange-1 text-orange-10 q-mb-md" rounded>
              <template #avatar>
                <q-icon name="sync_problem" color="orange-9" />
              </template>
              Бот должен работать, но не запущен. Идут повторные попытки.
              <div v-if="detail.runtime && detail.runtime.lastError" class="text-caption q-mt-xs">
                {{ detail.runtime.lastError }}
              </div>
            </q-banner>

            <q-card flat bordered>
              <q-card-section>
                <q-input
                  v-model="editForm.name"
                  dense
                  outlined
                  label="Имя бота"
                  :disable="saving || detail.active"
                  maxlength="120"
                />

                <div class="text-caption text-grey q-mt-sm">
                  Желаемое состояние:
                  <q-badge
                    :color="detail.active ? 'primary' : 'grey-6'"
                    :label="detail.active ? 'должен работать' : 'остановлен'"
                    outline
                  />
                  <span class="q-ml-sm">Runtime:</span>
                  <q-badge
                    :color="stateColor(detail.runtime && detail.runtime.state)"
                    :label="stateLabel(detail.runtime && detail.runtime.state)"
                    outline
                  />
                </div>

                <div v-if="detail.runtime && detail.runtime.lastError" class="text-caption text-negative q-mt-xs">
                  {{ detail.runtime.lastError }}
                </div>

                <!-- Живость стримов: молчащий стрим внешне неотличим от спокойного рынка -->
                <div class="text-caption text-grey q-mt-xs">
                  Потоки:
                  <q-badge
                    :color="streams.marketData && streams.marketData.connected ? 'positive' : 'grey-6'"
                    :label="'цены ' + (streams.marketData && streams.marketData.connected ? '✓' : '—')"
                    outline
                    class="q-mr-xs"
                  />
                  <q-badge
                    :color="streams.orders && streams.orders.connected ? 'positive' : 'grey-6'"
                    :label="'ордера ' + (streams.orders && streams.orders.connected ? '✓' : '—')"
                    outline
                  />
                  <span v-if="lastStreamEvent" class="q-ml-sm">
                    последнее событие: {{ lastStreamEvent }}
                  </span>
                </div>

                <div class="text-caption text-grey q-mt-xs">
                  ID: <span class="mono">{{ detail.id }}</span>
                </div>
              </q-card-section>

              <q-separator />

              <q-card-section class="q-gutter-md">
                <div class="row q-col-gutter-md">
                  <div class="col-12 col-md-6">
                    <q-select
                      v-model="editForm.strategyType"
                      :options="strategyTypes"
                      label="Стратегия"
                      outlined
                      dense
                      :disable="saving || detail.active"
                    />
                  </div>

                  <div class="col-12 col-md-6">
                    <q-select
                      v-model="editForm.exchangeConnectionId"
                      :options="connectionOptions"
                      option-value="value"
                      option-label="label"
                      emit-value
                      map-options
                      label="Подключение"
                      outlined
                      dense
                      :disable="saving || detail.active"
                    />
                  </div>

                  <!-- Для GRID — структурированная форма с проверкой безубытка;
                       для остальных стратегий пока сырой JSON. -->
                  <div v-if="editForm.strategyType === 'GRID'" class="col-12">
                    <GridConfigForm
                      v-model="editForm.strategyConfig"
                      :disable="saving || detail.active"
                      :commission-rate="commissionRate"
                      :price-increment="priceIncrement"
                      :open-orders="state.orders"
                    />
                  </div>

                  <div v-else class="col-12">
                    <q-input
                      v-model="editForm.strategyConfig"
                      label="Конфигурация стратегии (JSON)"
                      outlined
                      dense
                      type="textarea"
                      autogrow
                      input-class="mono"
                      :disable="saving || detail.active"
                      :error="!!configError"
                      :error-message="configError"
                    />
                  </div>
                </div>

                <div v-if="detail.active" class="text-caption text-grey">
                  Настройки работающего бота менять нельзя — у него выставлены ордера.
                  Остановите бота, чтобы отредактировать.
                </div>

                <div class="row justify-end">
                  <q-btn
                    color="primary"
                    label="Сохранить"
                    :loading="saving"
                    :disable="saving || detail.active || !isDirty || !!configError"
                    @click="save"
                  />
                </div>
              </q-card-section>

              <q-separator />

              <q-card-section>
                <div class="row q-col-gutter-md">
                  <div class="col-12 col-md-6">
                    <div class="text-caption text-grey">Создан</div>
                    <div class="mono">{{ formatInstant(detail.createdAt) }}</div>
                  </div>
                  <div class="col-12 col-md-6">
                    <div class="text-caption text-grey">Обновлён</div>
                    <div class="mono">{{ formatInstant(detail.updatedAt) }}</div>
                  </div>
                </div>
              </q-card-section>
            </q-card>

            <!-- Финансовый учет: прибыльность уже считает backend, здесь только отображение -->
            <q-card flat bordered class="q-mt-md">
              <q-card-section class="row items-center justify-between">
                <div class="text-subtitle2">
                  Доходность
                  <q-badge
                    :color="accounting.dryRun ? 'purple' : 'blue-grey'"
                    :label="accounting.dryRun ? 'бумажный режим' : 'боевой режим'"
                    outline
                    class="q-ml-sm"
                  />
                </div>
                <q-btn
                  dense
                  flat
                  round
                  icon="refresh"
                  :loading="accountingLoading"
                  :disable="!detail"
                  @click="loadAccounting(detail && detail.id)"
                />
              </q-card-section>

              <q-separator />

              <q-card-section>
                <div class="accounting-grid">
                  <div class="metric-tile">
                    <div class="metric-label">Реализованный P/L</div>
                    <div class="metric-value" :class="moneyTone(accounting.realizedPnl)">
                      {{ formatSignedMoney(accounting.realizedPnl, accounting.currency) }}
                    </div>
                  </div>
                  <div class="metric-tile">
                    <div class="metric-label">Денежный поток</div>
                    <div class="metric-value" :class="moneyTone(accounting.cashFlow)">
                      {{ formatSignedMoney(accounting.cashFlow, accounting.currency) }}
                    </div>
                  </div>
                  <div class="metric-tile">
                    <div class="metric-label">Открытая себестоимость</div>
                    <div class="metric-value mono">
                      {{ formatMoneyWithCurrency(accounting.costBasisOpen, accounting.currency) }}
                    </div>
                  </div>
                  <div class="metric-tile">
                    <div class="metric-label">Комиссии</div>
                    <div class="metric-value text-negative">
                      {{ formatFeeWithCurrency(accounting.paidCommission, accounting.currency) }}
                    </div>
                  </div>
                  <div class="metric-tile">
                    <div class="metric-label">Открыто лотов</div>
                    <div class="metric-value mono">{{ accounting.openLots || 0 }}</div>
                  </div>
                  <div class="metric-tile">
                    <div class="metric-label">Средняя входа</div>
                    <div class="metric-value mono">
                      {{ formatMoneyWithCurrency(accounting.averageEntryPrice, accounting.currency) }}
                    </div>
                  </div>
                </div>
              </q-card-section>

              <q-separator />

              <q-markup-table flat dense wrap-cells class="ledger-table">
                <thead>
                  <tr>
                    <th class="text-left">Тип</th>
                    <th class="text-left">Сторона</th>
                    <th class="text-right">Цена</th>
                    <th class="text-right">Лоты</th>
                    <th class="text-right">Оборот</th>
                    <th class="text-right">Комиссия</th>
                    <th class="text-right">Сумма</th>
                    <th class="text-left">Время</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in ledgerRows" :key="row.seq">
                    <td>
                      <q-badge :color="ledgerTypeColor(row.entryType)" :label="ledgerTypeLabel(row.entryType)" outline />
                    </td>
                    <td>
                      <q-badge
                        v-if="row.side"
                        :color="row.side === 'BUY' ? 'green-7' : 'red-7'"
                        :label="row.side === 'BUY' ? 'покупка' : 'продажа'"
                        outline
                      />
                      <span v-else>—</span>
                    </td>
                    <td class="text-right mono">{{ formatMoney(row.price) }}</td>
                    <td class="text-right mono">{{ formatLots(row) }}</td>
                    <td class="text-right mono">{{ formatMoneyWithCurrency(row.grossAmount, row.currency) }}</td>
                    <td class="text-right mono">
                      {{ formatFeeWithCurrency(row.commission, row.currency) }}
                      <q-icon v-if="row.commissionEstimated" name="schedule" size="14px" class="q-ml-xs text-warning">
                        <q-tooltip>Комиссия оценочная, будет уточнена после сверки с биржей</q-tooltip>
                      </q-icon>
                    </td>
                    <td class="text-right mono" :class="moneyTone(row.amount)">
                      {{ formatSignedMoney(row.amount, row.currency) }}
                    </td>
                    <td class="mono text-caption">{{ formatInstant(row.ts) }}</td>
                  </tr>
                  <tr v-if="ledgerRows.length === 0">
                    <td colspan="8" class="text-grey">
                      Записей учета пока нет
                    </td>
                  </tr>
                </tbody>
              </q-markup-table>
            </q-card>

            <!-- Что бот делает прямо сейчас -->
            <q-card flat bordered class="q-mt-md">
              <q-card-section class="row items-center justify-between">
                <div class="text-subtitle2">
                  Состояние
                  <q-badge v-if="state.dryRun" color="purple" label="бумажный режим" outline class="q-ml-sm" />
                </div>
                <div class="row items-center q-gutter-md text-caption">
                  <div>Позиция: <b class="mono">{{ state.positionLots }}</b> лот(ов)</div>
                  <div>Занято: <b class="mono">{{ formatMoney(state.reservedByBuyOrders) }}</b></div>
                  <div>
                    Активных: <b class="mono">{{ state.openOrdersCount }}</b>
                  </div>
                  <div v-if="state.queueSize > 0" class="text-orange-9">
                    Очередь: {{ state.queueSize }}
                  </div>
                </div>
              </q-card-section>

              <q-separator />

              <q-markup-table flat dense wrap-cells class="orders-table">
                <thead>
                  <tr>
                    <th class="text-left">Уровень</th>
                    <th class="text-left">Сторона</th>
                    <th class="text-right">Цена</th>
                    <th class="text-right">Лоты</th>
                    <th class="text-right">Комиссия</th>
                    <th class="text-left">Статус</th>
                    <th class="text-left">Время</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="o in state.orders" :key="o.id">
                    <td>{{ o.gridLevel === null ? '—' : o.gridLevel }}</td>
                    <td>
                      <q-badge
                        :color="o.side === 'BUY' ? 'green-7' : 'red-7'"
                        :label="o.side === 'BUY' ? 'покупка' : 'продажа'"
                        outline
                      />
                    </td>
                    <td class="text-right mono">{{ formatMoney(o.limitPrice) }}</td>
                    <td class="text-right mono">{{ o.executedLots }}/{{ o.requestedLots }}</td>
                    <td class="text-right mono">
                      {{ formatOrderFee(o) }}
                      <q-icon v-if="o.fee && !o.feeActual" name="schedule" size="14px" class="q-ml-xs text-warning">
                        <q-tooltip>Комиссия оценочная</q-tooltip>
                      </q-icon>
                    </td>
                    <td>
                      <q-badge :color="orderStatusColor(o.status)" :label="orderStatusLabel(o.status)" outline />
                      <q-tooltip v-if="o.lastError">{{ o.lastError }}</q-tooltip>
                    </td>
                    <td class="mono text-caption">{{ formatInstant(o.updatedAt) }}</td>
                  </tr>
                  <tr v-if="state.orders.length === 0">
                    <td colspan="7" class="text-grey">Ордеров пока нет</td>
                  </tr>
                </tbody>
              </q-markup-table>
            </q-card>

            <!-- Лента событий -->
            <q-card flat bordered class="q-mt-md">
              <q-card-section class="row items-center justify-between">
                <div class="text-subtitle2">События</div>
                <q-select
                  v-model="eventLevelFilter"
                  :options="['все', 'INFO', 'WARN', 'ERROR']"
                  dense
                  outlined
                  options-dense
                  style="min-width: 120px"
                />
              </q-card-section>

              <q-separator />

              <q-list separator dense class="events-list">
                <q-item v-for="e in filteredEvents" :key="e.id">
                  <q-item-section side>
                    <q-badge :color="eventLevelColor(e.level)" :label="e.level" outline />
                  </q-item-section>
                  <q-item-section>
                    <q-item-label caption>
                      <span class="mono">{{ formatInstant(e.ts) }}</span>
                      · {{ eventTypeLabel(e.type) }}
                      <q-icon v-if="eventIsNotifiable(e)" name="send" size="12px" class="q-ml-xs">
                        <q-tooltip>Дублируется в Telegram</q-tooltip>
                      </q-icon>
                    </q-item-label>
                    <q-item-label>{{ e.message }}</q-item-label>
                  </q-item-section>
                </q-item>
                <q-item v-if="filteredEvents.length === 0">
                  <q-item-section class="text-grey">Событий пока нет</q-item-section>
                </q-item>
              </q-list>

              <q-card-section class="text-caption text-grey">
                В журнал попадает всё; в Telegram — только отмеченное, и то через
                ограничитель частоты. Если уведомления скрывались, бот пришлёт сводку.
              </q-card-section>
            </q-card>
          </div>
        </q-scroll-area>
      </div>
    </div>

    <!-- CREATE dialog -->
    <q-dialog v-model="createDialog">
      <q-card style="min-width: 560px">
        <q-card-section class="row items-center justify-between">
          <div class="text-subtitle1">Создать бота</div>
          <q-btn dense flat round icon="close" v-close-popup />
        </q-card-section>

        <q-separator />

        <q-card-section class="q-gutter-md">
          <q-input v-model="createForm.name" label="Имя бота" outlined dense :disable="createLoading" maxlength="120" />

          <q-select
            v-model="createForm.strategyType"
            :options="strategyTypes"
            label="Стратегия"
            outlined
            dense
            :disable="createLoading"
          />

          <q-select
            v-model="createForm.exchangeConnectionId"
            :options="connectionOptions"
            option-value="value"
            option-label="label"
            emit-value
            map-options
            label="Подключение"
            outlined
            dense
            :disable="createLoading"
          />

          <div v-if="connectionOptions.length === 0" class="text-caption text-negative">
            Нет ни одного подключения. Сначала создайте подключение в разделе «Биржи».
          </div>
        </q-card-section>

        <q-card-actions align="right" class="q-pa-md">
          <q-btn flat label="Отмена" v-close-popup :disable="createLoading" />
          <q-btn color="primary" label="Создать" :loading="createLoading" :disable="!canCreate" @click="doCreate" />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- delete confirm -->
    <q-dialog v-model="confirmDelete">
      <q-card style="min-width: 420px">
        <q-card-section class="row items-center q-gutter-sm">
          <q-icon name="warning" color="negative" size="md" />
          <div class="text-subtitle1">Удалить бота?</div>
        </q-card-section>

        <q-card-section class="text-grey">
          Это действие нельзя отменить.
        </q-card-section>

        <q-card-actions align="right">
          <q-btn flat label="Отмена" v-close-popup />
          <q-btn color="negative" label="Удалить" :loading="deleteLoading" :disable="!detail" @click="doDelete" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, inject } from 'vue'
import { apiClient, getErrorMessage } from 'src/services/apiClient'
import {
  stateColor, stateLabel, formatInstant, isRuntimeActive, isRuntimeInactive,
  eventTypeLabel, isNotifiableEvent
} from 'src/services/runtimeState'
import GridConfigForm from 'components/GridConfigForm.vue'

const toast = inject('toast')

// Ставка комиссии и шаг цены приходят от подключения: без них проверка
// безубытка в форме считала бы не то, что бэкенд.
const commissionRate = ref(0.003)
const priceIncrement = ref(0.01)

async function loadConnectionEconomics (connectionId) {
  if (!connectionId) return
  try {
    const conn = await apiClient.get(`/api/v1/exchange-connections/${connectionId}`)
    if (conn?.settings?.commissionRate != null) {
      commissionRate.value = Number(conn.settings.commissionRate)
    }
  } catch (e) {
    console.debug(e)
  }
}

const list = ref([])
const listLoading = ref(false)

const selectedId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)
const runtimeLoading = ref(false)

const actionLoading = ref(false)
const saving = ref(false)
const confirmDelete = ref(false)
const deleteLoading = ref(false)

const strategyTypes = ref([])
const connections = ref([])

const createDialog = ref(false)
const createLoading = ref(false)
const createForm = reactive({ name: '', strategyType: null, exchangeConnectionId: null })

const editForm = reactive({ name: '', strategyType: null, exchangeConnectionId: null, strategyConfig: '{}' })
let editOriginal = {}

let pollTimer = null

const connectionOptions = computed(() =>
  connections.value.map(c => ({ value: c.id, label: `${c.name} (${c.exchange})` }))
)

const canCreate = computed(() =>
  !!createForm.name.trim() &&
  !!createForm.strategyType &&
  !!createForm.exchangeConnectionId &&
  !createLoading.value
)

const configError = computed(() => {
  const raw = (editForm.strategyConfig || '').trim()
  if (!raw) return ''
  try {
    JSON.parse(raw)
    return ''
  } catch (e) {
    return 'Некорректный JSON: ' + e.message
  }
})

const isDirty = computed(() =>
  editForm.name !== editOriginal.name ||
  editForm.strategyType !== editOriginal.strategyType ||
  editForm.exchangeConnectionId !== editOriginal.exchangeConnectionId ||
  editForm.strategyConfig !== editOriginal.strategyConfig
)

// Что бот делает прямо сейчас: позиция, заявки, очередь событий.
const state = ref({
  running: false, dryRun: false, positionLots: 0,
  reservedByBuyOrders: null, openOrdersCount: 0, queueSize: 0, orders: []
})
const botEvents = ref([])
const eventLevelFilter = ref('все')
const accountingLoading = ref(false)
const accounting = ref(emptyAccounting())
const ledger = ref([])

const filteredEvents = computed(() =>
  eventLevelFilter.value === 'все'
    ? botEvents.value
    : botEvents.value.filter(e => e.level === eventLevelFilter.value)
)

const ledgerRows = computed(() => ledger.value.slice(0, 50))

function emptyAccounting () {
  return {
    dryRun: false,
    cashFlow: 0,
    costBasisOpen: 0,
    realizedPnl: 0,
    paidCommission: 0,
    openLots: 0,
    averageEntryPrice: null,
    currency: null
  }
}

function formatMoney (value) {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  return Number.isNaN(n) ? String(value) : n.toLocaleString('ru-RU', { maximumFractionDigits: 4 })
}

function formatMoneyWithCurrency (value, currency) {
  const amount = formatMoney(value)
  return amount === '—' ? amount : `${amount}${currency ? ` ${currency}` : ''}`
}

function formatFee (value) {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  return Number.isNaN(n) ? String(value) : n.toLocaleString('ru-RU', { maximumFractionDigits: 9 })
}

function formatFeeWithCurrency (value, currency) {
  const amount = formatFee(value)
  return amount === '—' ? amount : `${amount}${currency ? ` ${currency}` : ''}`
}

function formatSignedMoney (value, currency) {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  if (Number.isNaN(n)) return String(value)
  const prefix = n > 0 ? '+' : ''
  return `${prefix}${formatMoney(n)}${currency ? ` ${currency}` : ''}`
}

function moneyTone (value) {
  const n = Number(value)
  if (Number.isNaN(n) || n === 0) return 'text-grey-8'
  return n > 0 ? 'text-positive' : 'text-negative'
}

function formatOrderFee (order) {
  if (order?.fee === null || order?.fee === undefined) return '—'
  return formatFeeWithCurrency(order.fee, order.feeCurrency)
}

function formatLots (row) {
  if (row?.lots === null || row?.lots === undefined) return '—'
  const lotSize = row.lotSize && row.lotSize !== 1 ? ` × ${row.lotSize}` : ''
  return `${row.lots}${lotSize}`
}

function orderStatusColor (status) {
  switch (status) {
    case 'FILLED': return 'positive'
    case 'NEW':
    case 'PARTIALLY_FILLED': return 'primary'
    // PENDING — исход постановки неизвестен, его разрешит сверка.
    case 'PENDING':
    case 'UNKNOWN': return 'warning'
    case 'REJECTED': return 'negative'
    default: return 'grey-6'
  }
}

function orderStatusLabel (status) {
  switch (status) {
    case 'PENDING': return 'ожидает сверки'
    case 'NEW': return 'выставлен'
    case 'PARTIALLY_FILLED': return 'частично'
    case 'FILLED': return 'исполнен'
    case 'CANCELLED': return 'снят'
    case 'REJECTED': return 'отклонён'
    case 'EXPIRED': return 'истёк'
    default: return status
  }
}

function eventLevelColor (level) {
  switch (level) {
    case 'ERROR': return 'negative'
    case 'WARN': return 'warning'
    default: return 'grey-6'
  }
}

function eventIsNotifiable (event) {
  return event?.notifiable ?? isNotifiableEvent(event?.type)
}

function ledgerTypeLabel (type) {
  switch (type) {
    case 'TRADE_BUY': return 'Покупка'
    case 'TRADE_SELL': return 'Продажа'
    case 'COMMISSION_CORRECTION': return 'Коррекция комиссии'
    case 'CYCLE_RESULT': return 'Результат цикла'
    case 'GRID_REPLACED': return 'Сетка заменена'
    default: return type || '—'
  }
}

function ledgerTypeColor (type) {
  switch (type) {
    case 'TRADE_BUY': return 'green-7'
    case 'TRADE_SELL': return 'red-7'
    case 'COMMISSION_CORRECTION': return 'warning'
    case 'CYCLE_RESULT': return 'primary'
    default: return 'grey-6'
  }
}

async function loadState (id) {
  if (!id) return
  try {
    const [s, ev, acc, led] = await Promise.all([
      apiClient.get(`/api/v1/bots/${id}/state`),
      apiClient.get(`/api/v1/bots/${id}/events`, { params: { limit: 100 } }),
      apiClient.get(`/api/v1/bots/${id}/accounting`),
      apiClient.get(`/api/v1/bots/${id}/ledger`)
    ])
    state.value = s
    botEvents.value = Array.isArray(ev) ? ev : []
    accounting.value = acc || emptyAccounting()
    ledger.value = Array.isArray(led) ? led : []
  } catch (e) {
    console.debug(e)
  }
}

async function loadAccounting (id) {
  if (!id) return
  accountingLoading.value = true
  try {
    const [acc, led] = await Promise.all([
      apiClient.get(`/api/v1/bots/${id}/accounting`),
      apiClient.get(`/api/v1/bots/${id}/ledger`)
    ])
    accounting.value = acc || emptyAccounting()
    ledger.value = Array.isArray(led) ? led : []
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось загрузить доходность'))
  } finally {
    accountingLoading.value = false
  }
}

// Стримы принадлежат подключению бота — по ним видно, жив ли он на самом деле.
const streams = ref({ supported: false, marketData: null, orders: null })

const lastStreamEvent = computed(() => {
  const times = [streams.value.marketData, streams.value.orders]
    .map(h => h && h.lastEventAt)
    .filter(Boolean)
    .map(t => new Date(t).getTime())
  if (times.length === 0) return null
  return formatInstant(new Date(Math.max(...times)).toISOString())
})

async function loadStreams (connectionId) {
  if (!connectionId) {
    streams.value = { supported: false, marketData: null, orders: null }
    return
  }
  try {
    streams.value = await apiClient.get(`/api/v1/exchange-connections/${connectionId}/streams`)
  } catch (e) {
    console.debug(e)
  }
}

/** Бот, которого пользователь просил запустить, но который не работает. */
function needsAttention (bot) {
  return !!bot && bot.active && !isRuntimeActive(bot.runtime && bot.runtime.state)
}

const blockedByConnection = computed(() =>
  !!detail.value && detail.value.active && !isRuntimeActive(detail.value.exchangeConnectionState)
)

onMounted(async () => {
  await Promise.all([loadStrategyTypes(), loadConnections()])
  await loadList()
  pollTimer = setInterval(refreshQuietly, 4000)
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})

/** Фоновое обновление статусов: не трогает поля, которые пользователь редактирует. */
async function refreshQuietly () {
  try {
    list.value = await apiClient.get('/api/v1/bots')
    if (selectedId.value && detail.value) {
      const fresh = await apiClient.get(`/api/v1/bots/${selectedId.value}`)
      detail.value.active = fresh.active
      detail.value.runtime = fresh.runtime
      detail.value.exchangeConnectionState = fresh.exchangeConnectionState
      await Promise.all([loadStreams(fresh.exchangeConnectionId), loadState(fresh.id)])
    }
  } catch (e) {
    console.debug(e)
  }
}

async function loadStrategyTypes () {
  try {
    const data = await apiClient.get('/api/v1/bots/strategy-types')
    strategyTypes.value = Array.isArray(data) ? data : []
  } catch (e) {
    console.error(e)
  }
}

async function loadConnections () {
  try {
    const data = await apiClient.get('/api/v1/exchange-connections', { params: { page: 0, size: 100 } })
    connections.value = Array.isArray(data?.content) ? data.content : []
  } catch (e) {
    console.error(e)
  }
}

async function loadList () {
  listLoading.value = true
  try {
    list.value = await apiClient.get('/api/v1/bots')

    if (selectedId.value && !list.value.some(x => x.id === selectedId.value)) {
      selectedId.value = null
      detail.value = null
    }
    if (!selectedId.value && list.value.length > 0) {
      await select(list.value[0].id)
    }
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось загрузить ботов'))
  } finally {
    listLoading.value = false
  }
}

async function select (id) {
  if (!id || selectedId.value === id) return
  selectedId.value = id
  await loadDetail(id)
}

async function loadDetail (id) {
  if (!id) return
  detailLoading.value = true
  runtimeLoading.value = true
  try {
    const d = await apiClient.get(`/api/v1/bots/${id}`)
    detail.value = d

    editForm.name = d.name || ''
    editForm.strategyType = d.strategyType
    editForm.exchangeConnectionId = d.exchangeConnectionId
    editForm.strategyConfig = d.strategyConfig || '{}'
    editOriginal = { ...editForm }

    await Promise.all([
      loadStreams(d.exchangeConnectionId),
      loadState(d.id),
      loadConnectionEconomics(d.exchangeConnectionId),
      // Список подключений мог устареть — без него селект покажет голый UUID.
      connections.value.some(c => c.id === d.exchangeConnectionId) ? Promise.resolve() : loadConnections()
    ])
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось загрузить бота'))
  } finally {
    detailLoading.value = false
    runtimeLoading.value = false
  }
}

async function save () {
  const id = detail.value?.id
  if (!id) return

  saving.value = true
  try {
    await apiClient.put(`/api/v1/bots/${id}`, {
      name: editForm.name.trim(),
      strategyType: editForm.strategyType,
      exchangeConnectionId: editForm.exchangeConnectionId,
      strategyConfig: editForm.strategyConfig
    })
    toast?.ok('Сохранено')
    await loadDetail(id)
    await loadList()
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось сохранить'))
  } finally {
    saving.value = false
  }
}

async function doActivateDeactivate () {
  const id = detail.value?.id
  if (!id) return

  const targetActive = !detail.value.active
  actionLoading.value = true
  try {
    await apiClient.post(`/api/v1/bots/${id}/${targetActive ? 'activate' : 'deactivate'}`)
    await loadDetail(id)
    await loadList()

    const st = detail.value?.runtime?.state
    if (targetActive && isRuntimeActive(st)) {
      toast?.ok('Бот запущен')
    } else if (!targetActive && isRuntimeInactive(st)) {
      toast?.ok('Бот остановлен')
    } else if (detail.value?.runtime?.lastError) {
      toast?.warn(detail.value.runtime.lastError, { timeout: 6000 })
    }
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось выполнить действие'))
  } finally {
    actionLoading.value = false
  }
}

function openCreate () {
  createForm.name = ''
  createForm.strategyType = strategyTypes.value.includes('GRID') ? 'GRID' : null
  createForm.exchangeConnectionId = connectionOptions.value.length === 1
    ? connectionOptions.value[0].value
    : null
  createDialog.value = true
  loadConnections()
}

async function doCreate () {
  if (!canCreate.value) return
  createLoading.value = true
  try {
    const id = await apiClient.post('/api/v1/bots', {
      name: createForm.name.trim(),
      strategyType: createForm.strategyType,
      exchangeConnectionId: createForm.exchangeConnectionId,
      strategyConfig: '{}'
    })
    createDialog.value = false
    await loadList()
    await select(id)
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось создать бота'))
  } finally {
    createLoading.value = false
  }
}

async function doDelete () {
  const id = detail.value?.id
  if (!id) return
  deleteLoading.value = true
  try {
    await apiClient.delete(`/api/v1/bots/${id}`)
    confirmDelete.value = false
    selectedId.value = null
    detail.value = null
    await loadList()
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось удалить бота'))
  } finally {
    deleteLoading.value = false
  }
}
</script>

<style scoped>
.page-root {
  height: 100%;
}

.main-row {
  height: calc(100vh - 160px);
  display: flex;
  gap: 12px;
}

.left-pane {
  width: 28%;
  min-width: 260px;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--q-color-grey-4);
  border-radius: 8px;
  overflow: hidden;
}

.right-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--q-color-grey-4);
  border-radius: 8px;
  overflow: hidden;
}

.pane-header {
  padding: 10px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.pane-scroll {
  flex: 1;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
}

/* Таблица ордеров может быть широкой — скроллим её саму, а не страницу */
.orders-table {
  max-height: 320px;
  overflow: auto;
}

.accounting-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(140px, 1fr));
  gap: 10px;
}

.metric-tile {
  min-height: 68px;
  padding: 10px 12px;
  border: 1px solid var(--q-color-grey-4);
  border-radius: 8px;
  background: #fff;
}

.metric-label {
  color: #757575;
  font-size: 12px;
  line-height: 1.2;
}

.metric-value {
  margin-top: 6px;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.2;
}

.ledger-table {
  max-height: 320px;
  overflow: auto;
}

.events-list {
  max-height: 360px;
  overflow: auto;
}

@media (max-width: 900px) {
  .accounting-grid {
    grid-template-columns: repeat(2, minmax(140px, 1fr));
  }
}

@media (max-width: 620px) {
  .accounting-grid {
    grid-template-columns: 1fr;
  }
}
</style>
