<template>
  <q-page class="q-pa-md page-root">
    <!-- header row -->
    <div class="row items-center justify-between q-mb-md">
      <div class="text-h5">Биржи</div>

      <q-btn
        color="primary"
        icon="add"
        label="Создать"
        @click="openCreate()"
      />
    </div>

    <div class="main-row">
      <!-- LEFT: list -->
      <div class="left-pane">
        <div class="pane-header">
          <div class="text-subtitle1">Подключения</div>
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
                <q-item-label class="text-weight-medium">
                  {{ item.name || 'Без названия' }}
                </q-item-label>
                <q-item-label caption>
                  {{ item.exchange }} · {{ item.active ? 'должно работать' : 'остановлено' }}
                </q-item-label>
              </q-item-section>

              <q-item-section side>
                <div class="row items-center q-gutter-sm no-wrap">
                  <connection-valuation-cell :valuation="item.valuation" />
                  <!-- Подключение, которое должно работать, но не работает: идут повторы -->
                  <q-icon
                    v-if="item.active && !isRuntimeActive(item.runtimeState)"
                    name="sync_problem"
                    color="warning"
                    size="xs"
                  >
                    <q-tooltip>Должно работать, но не поднято — идут повторные попытки</q-tooltip>
                  </q-icon>
                  <q-badge
                    :color="stateColor(item.runtimeState)"
                    :label="stateLabel(item.runtimeState)"
                    outline
                  />
                </div>
              </q-item-section>
            </q-item>

            <q-item v-if="!listLoading && list.length === 0">
              <q-item-section>
                <q-item-label class="text-grey">Подключений пока нет</q-item-label>
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
              color="primary"
              :label="detail.active ? 'Остановить' : 'Запустить'"
              :loading="actionLoading || desiredActive !== null"
              :disable="detailLoading || actionLoading || desiredActive !== null"
              @click="doActivateDeactivate"
            />

            <q-btn
              v-if="detail"
              dense
              flat
              icon="library_books"
              label="Справочник"
              :loading="catalogSyncing"
              :disable="detailLoading || catalogSyncing"
              @click="syncCatalog"
            >
              <q-tooltip>Обновить справочник инструментов этой биржи</q-tooltip>
            </q-btn>

            <q-btn
              dense
              flat
              icon="refresh"
              :disable="!detail || detailLoading || runtimeLoading"
              :loading="runtimeLoading"
              @click="loadRuntime(detail?.id)"
            />

            <q-btn
              dense
              flat
              icon="delete"
              color="negative"
              :disable="!detail || detailLoading || actionLoading || desiredActive !== null"
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
            Выбери подключение слева.
          </div>

          <div v-else-if="detail" class="q-pa-md">
            <q-card flat bordered>
              <q-card-section>
                <div class="row items-center q-col-gutter-sm">
                  <div class="col">
                    <q-input
                      v-model="editName"
                      dense
                      outlined
                      label="Имя подключения"
                      :disable="nameSaving"
                      maxlength="120"
                    />
                  </div>
                  <div class="col-auto">
                    <q-btn
                      v-if="isNameDirty"
                      dense
                      round
                      icon="check"
                      color="positive"
                      :loading="nameSaving"
                      :disable="nameSaving"
                      @click="saveName"
                    />
                  </div>
                </div>
                <div class="text-caption text-grey">
                  {{ detail.exchange }} · {{ detail.active ? 'Активно' : 'Выключено' }}
                </div>
                <div class="text-caption text-grey q-mt-xs">
                  Желаемое состояние:
                  <q-badge
                    :color="detail.active ? 'primary' : 'grey-6'"
                    :label="detail.active ? 'должно работать' : 'остановлено'"
                    outline
                  />
                  <span class="q-ml-sm">Runtime:</span>
                  <q-badge :color="stateColor(runtime?.state)" :label="stateLabel(runtime?.state)" outline />
                  <span v-if="runtime?.lastError" class="q-ml-sm text-negative">{{ runtime.lastError }}</span>
                </div>
                <div class="text-caption text-grey q-mt-xs">
                  ID: <span class="mono">{{ detail.id }}</span>
                </div>
              </q-card-section>

              <q-separator />

              <q-card-section class="q-gutter-md">
                <div class="row q-col-gutter-md">
                  <div class="col-12 col-md-6">
                    <div class="row items-center justify-between">
                      <div>
                        <div class="text-caption text-grey">API Key</div>
                        <div class="mono">{{ detail.apiKeyMasked || '—' }}</div>
                      </div>
                      <q-btn
                        dense
                        flat
                        icon="edit"
                        label="Ключи"
                        :disable="!detail || credentialsSaving"
                        @click="openCredentialsDialog"
                      />
                    </div>
                  </div>

                  <div class="col-12 col-md-3">
                    <div class="text-caption text-grey">Secret</div>
                    <q-badge
                      :label="detail.hasSecret ? 'Есть' : 'Нет'"
                      :color="detail.hasSecret ? 'positive' : 'grey-6'"
                      outline
                    />
                  </div>

                  <div class="col-12 col-md-3">
                    <div class="text-caption text-grey">Passphrase</div>
                    <q-badge
                      :label="detail.hasPassphrase ? 'Есть' : 'Нет'"
                      :color="detail.hasPassphrase ? 'positive' : 'grey-6'"
                      outline
                    />
                  </div>

                  <!--
                    Песочница показывается только у бирж, где она есть. У Poloniex её нет,
                    и переключатель означал бы, что торговля учебная, — тогда как деньги
                    были бы настоящими.
                  -->
                  <div v-if="supportsSandbox" class="col-12 col-md-6">
                    <div class="text-caption text-grey">Песочница</div>
                    <div class="row items-center q-gutter-sm">
                      <q-toggle
                        v-model="sandboxEnabled"
                        :disable="sandboxSaving || !detail"
                        @update:model-value="onSandboxToggle"
                      />
                      <q-badge
                        :label="sandboxEnabled ? 'Включена' : 'Выключена'"
                        :color="sandboxEnabled ? 'primary' : 'grey-6'"
                        outline
                      />
                    </div>
                  </div>
                  <div v-else class="col-12 col-md-6">
                    <div class="text-caption text-grey">Песочница</div>
                    <div class="text-caption text-grey-7">
                      У этой биржи её нет — проверяйте бота в бумажном режиме
                    </div>
                  </div>

                  <div class="col-12 col-md-6">
                    <div class="text-caption text-grey">Создано</div>
                    <div class="mono">{{ formatInstant(detail.createdAt) }}</div>
                  </div>

                  <div class="col-12 col-md-6">
                    <div class="text-caption text-grey">Обновлено</div>
                    <div class="mono">{{ formatInstant(detail.updatedAt) }}</div>
                  </div>
                </div>
              </q-card-section>
            </q-card>

            <!-- Живость стримов -->
            <q-card flat bordered class="q-mt-md">
              <q-card-section class="row items-center justify-between">
                <div class="text-subtitle2">Потоковые данные</div>
                <q-badge
                  :color="streams.supported ? 'primary' : 'grey-6'"
                  :label="streams.supported ? 'поддерживается' : 'нет подключения'"
                  outline
                />
              </q-card-section>

              <q-separator />

              <q-list separator>
                <q-item v-for="s in streamRows" :key="s.key">
                  <q-item-section>
                    <q-item-label>{{ s.title }}</q-item-label>
                    <q-item-label caption>
                      Последнее событие: {{ s.lastEvent }}
                      <span v-if="s.reconnects > 0"> · переподключений: {{ s.reconnects }}</span>
                    </q-item-label>
                    <q-item-label v-if="s.error" caption class="text-negative">{{ s.error }}</q-item-label>
                  </q-item-section>
                  <q-item-section side>
                    <q-badge
                      :color="s.connected ? 'positive' : 'grey-6'"
                      :label="s.connected ? 'подключён' : 'нет'"
                      outline
                    />
                  </q-item-section>
                </q-item>
              </q-list>

              <q-card-section class="text-caption text-grey">
                Стрим не отменяет сверку: за время разрыва события теряются безвозвратно,
                поэтому после каждого переподключения состояние пересинхронизируется запросом.
              </q-card-section>
            </q-card>

            <!-- Счёт и параметры тарифа -->
            <q-card flat bordered class="q-mt-md">
              <q-card-section class="row items-center justify-between">
                <div class="text-subtitle2">Счёт и параметры</div>
                <q-btn
                  dense
                  flat
                  label="Обновить счета"
                  icon="sync"
                  :loading="accountsLoading"
                  :disable="!isRuntimeActive(runtime?.state)"
                  @click="loadAccounts(detail.id)"
                />
              </q-card-section>

              <q-separator />

              <q-card-section class="q-gutter-md">
                <q-banner
                  v-if="!isRuntimeActive(runtime?.state) && !accounts.length"
                  dense
                  class="bg-grey-2"
                  rounded
                >
                  <template #avatar>
                    <q-icon name="info" color="grey-7" />
                  </template>
                  Список счетов отдаёт только запущенное подключение, а менять параметры
                  можно только на остановленном. Запустите его, нажмите «Обновить счета»,
                  затем остановите — список сохранится, и счёт станет доступен для выбора.
                  Если счёт на бирже единственный, оставьте поле пустым: он подставится сам.
                </q-banner>

                <q-select
                  v-model="settingsForm.accountId"
                  :options="accountOptions"
                  option-value="value"
                  option-label="label"
                  emit-value
                  map-options
                  label="Торговый счёт"
                  outlined
                  dense
                  clearable
                  :disable="settingsSaving || detail.active"
                  hint="Пусто — подойдёт, только если счёт единственный"
                />

                <q-input
                  v-model="settingsForm.baseCurrency"
                  label="Расчётная валюта"
                  outlined
                  dense
                  clearable
                  :disable="settingsSaving || detail.active"
                  hint="В ней считается баланс подключения. Пусто — спросим у биржи (у брокера RUB, у Poloniex USDT)"
                />

                <div class="text-caption text-grey">
                  Баланс подключения — это деньги расчётной валюты плюс всё остальное
                  на счёте по текущей цене. Купленное не перестаёт быть вашим оттого,
                  что перестало быть деньгами.
                </div>

                <q-separator />

                <q-toggle
                  v-model="settingsForm.marginEnabled"
                  label="Разрешить маржинальную торговлю"
                  :disable="settingsSaving || detail.active"
                />
                <q-banner v-if="settingsForm.marginEnabled" dense class="bg-red-1 text-red-9">
                  <template #avatar><q-icon name="warning" /></template>
                  Ботам этого подключения станет доступен шорт — продажа того, чего нет.
                  Убыток по короткой позиции сверху ничем не ограничен, а брокер вправе
                  закрыть её сам, по своей цене. Само по себе разрешение ничего не
                  включает: маржинальный режим ещё нужно включить у конкретного бота.
                  <div class="q-mt-xs">
                    Маржинальная торговля должна быть подключена и на самом счёте
                    у брокера — через API это не включается.
                  </div>
                </q-banner>

                <template v-if="usesManualFee">
                  <q-input
                    v-model.number="settingsForm.commissionPercent"
                    label="Комиссия за сделку, %"
                    outlined
                    dense
                    type="number"
                    step="0.001"
                    min="0"
                    :disable="settingsSaving || detail.active"
                    hint="Ваш тариф за одну сторону. «Инвестор» ≈ 0.3, «Трейдер» ≈ 0.05"
                  />

                  <div class="text-caption text-grey">
                    От этой ставки зависит проверка безубытка шага сетки: бот откажется
                    стартовать, если шаг не окупает комиссию за оборот
                    (сейчас {{ roundTripPercent }}%).
                  </div>
                </template>

                <div v-else class="text-caption text-grey">
                  Комиссию эта биржа отдаёт сама: ставка приходит по API и зависит от
                  вашего оборота за 30 дней. Вводить её вручную незачем — и опасно,
                  потому что расчёт безубытка всё равно возьмёт биржевую.
                </div>

                <div v-if="detail.active" class="text-caption text-grey">
                  Параметры активного подключения менять нельзя — остановите его.
                </div>

                <div class="row justify-end">
                  <q-btn
                    color="primary"
                    label="Сохранить"
                    :loading="settingsSaving"
                    :disable="settingsSaving || detail.active"
                    @click="saveSettings"
                  />
                </div>
              </q-card-section>
            </q-card>

            <!-- Боты этого подключения: видно, что остановит его выключение -->
            <q-card flat bordered class="q-mt-md">
              <q-card-section class="row items-center justify-between">
                <div class="text-subtitle2">Боты этого подключения</div>
                <q-badge :label="connectionBots.length" color="grey-7" outline />
              </q-card-section>

              <q-separator />

              <!-- Куда разошлись деньги счёта: сколько роздано, сколько лежит без дела -->
              <q-card-section v-if="detail.valuation && detail.valuation.botCount">
                <div class="money-grid">
                  <div>
                    <div class="money-label">Итого на подключении</div>
                    <div class="money-value mono">{{ connectionMoney.total }}</div>
                  </div>
                  <div>
                    <div class="money-label">Роздано ботам</div>
                    <div class="money-value mono">
                      {{ formatMoneyWithCurrency(detail.valuation.allocatedBudget,
                                                 detail.valuation.currency) }}
                    </div>
                  </div>
                  <div>
                    <div class="money-label">Баланс ботов</div>
                    <div class="money-value mono">
                      {{ formatMoneyWithCurrency(detail.valuation.botsBalance,
                                                 detail.valuation.currency) }}
                    </div>
                  </div>
                  <div>
                    <div class="money-label">Не задействовано</div>
                    <div
                      class="money-value mono"
                      :class="Number(detail.valuation.unallocatedCash) < 0 ? 'text-negative' : ''"
                    >
                      {{ formatMoneyWithCurrency(detail.valuation.unallocatedCash,
                                                 detail.valuation.currency) }}
                    </div>
                  </div>
                  <div>
                    <div class="money-label">P/L ботов</div>
                    <div class="money-value" :class="moneyTone(detail.valuation.botsPnl)">
                      {{ formatSignedMoney(detail.valuation.botsPnl, detail.valuation.currency) }}
                    </div>
                  </div>
                </div>
                <div
                  v-if="Number(detail.valuation.unallocatedCash) < 0"
                  class="text-caption text-negative q-mt-sm"
                >
                  Ботам роздано больше, чем есть свободных денег на счёте.
                </div>
                <div v-if="detail.valuation.incomplete" class="text-caption text-grey-7 q-mt-sm">
                  Не учтено ботов:
                  {{ detail.valuation.botCount - detail.valuation.valuedBotCount }} —
                  у них не задан бюджет либо есть позиция без актуальной цены.
                </div>
              </q-card-section>

              <q-separator v-if="detail.valuation && detail.valuation.botCount" />

              <q-list separator>
                <q-item v-for="b in connectionBots" :key="b.id" clickable to="/bots">
                  <q-item-section>
                    <q-item-label>{{ b.name }}</q-item-label>
                    <q-item-label caption>{{ b.strategyType }}</q-item-label>
                  </q-item-section>
                  <q-item-section side>
                    <div class="row items-center q-gutter-sm no-wrap">
                      <bot-valuation-cell :valuation="b.valuation" dense />
                      <q-badge
                        :color="stateColor(b.runtime && b.runtime.state)"
                        :label="stateLabel(b.runtime && b.runtime.state)"
                        outline
                      />
                    </div>
                  </q-item-section>
                </q-item>

                <q-item v-if="connectionBots.length === 0">
                  <q-item-section class="text-grey">
                    На этом подключении ботов нет
                  </q-item-section>
                </q-item>
              </q-list>

              <q-card-section v-if="runningBotsCount > 0 && detail.active" class="text-caption text-orange-9">
                <q-icon name="warning" size="xs" class="q-mr-xs" />
                Остановка подключения остановит {{ runningBotsCount }} работающих бот(ов).
              </q-card-section>
            </q-card>
          </div>
        </q-scroll-area>
      </div>
    </div>

    <!-- CREATE dialog -->
    <q-dialog v-model="createDialog">
      <q-card style="min-width: 520px">
        <q-card-section class="row items-center justify-between">
          <div class="text-subtitle1">Создать подключение</div>
          <q-btn dense flat round icon="close" v-close-popup />
        </q-card-section>

        <q-separator />

        <q-card-section class="q-gutter-md">
          <q-select
            v-model="createForm.exchange"
            :options="exchangeTypes"
            label="Тип биржи"
            outlined
            dense
            :disable="typesLoading || createLoading"
            :loading="typesLoading"
            clearable
          />

          <q-input
            v-model="createForm.name"
            label="Имя подключения"
            outlined
            dense
            :disable="createLoading"
            maxlength="120"
          />
          <div class="text-caption text-grey">
            API Key / Secret / Passphrase добавим на детальной странице после создания.
          </div>
        </q-card-section>

        <q-card-actions align="right" class="q-pa-md">
          <q-btn flat label="Отмена" v-close-popup :disable="createLoading" />
          <q-btn
            color="primary"
            label="Сохранить"
            :loading="createLoading"
            :disable="!canCreate"
            @click="doCreate"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- CREDENTIALS dialog -->
    <q-dialog v-model="credentialsDialog">
      <q-card style="min-width: 560px">
        <q-card-section class="row items-center justify-between">
          <div class="text-subtitle1">Ключи доступа</div>
          <q-btn dense flat round icon="close" v-close-popup />
        </q-card-section>

        <q-separator />

        <q-card-section class="q-gutter-md">
          <q-input
            v-model="credentialsForm.apiKey"
            label="API Key / Token"
            outlined
            dense
            :disable="credentialsSaving"
          />

          <q-input
            v-model="credentialsForm.apiSecret"
            label="API Secret"
            outlined
            dense
            :disable="credentialsSaving"
          />

          <q-input
            v-model="credentialsForm.passphrase"
            label="Passphrase"
            outlined
            dense
            :disable="credentialsSaving"
          />

          <div class="text-caption text-grey">
            Поля не обязательны. Можно сохранить пустые значения.
          </div>
        </q-card-section>

        <q-card-actions align="right" class="q-pa-md">
          <q-btn flat label="Отмена" v-close-popup :disable="credentialsSaving" />
          <q-btn
            color="primary"
            label="Сохранить"
            :loading="credentialsSaving"
            :disable="credentialsSaving || !detail"
            @click="saveCredentials"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- delete confirm -->
    <q-dialog v-model="confirmDelete">
      <q-card style="min-width: 420px">
        <q-card-section class="row items-center q-gutter-sm">
          <q-icon name="warning" color="negative" size="md" />
          <div class="text-subtitle1">Удалить подключение?</div>
        </q-card-section>

        <q-card-section class="text-grey">
          Это действие нельзя отменить.
        </q-card-section>

        <q-card-actions align="right">
          <q-btn flat label="Отмена" v-close-popup />
          <q-btn
            color="negative"
            label="Удалить"
            :loading="deleteLoading"
            :disable="!detail"
            @click="doDelete"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, reactive, onBeforeUnmount, inject } from 'vue'
import { apiClient, getErrorMessage } from 'src/services/apiClient'
import { stateColor, stateLabel, isRuntimeActive, isRuntimeInactive } from 'src/services/runtimeState'
import BotValuationCell from 'components/BotValuationCell.vue'
import ConnectionValuationCell from 'components/ConnectionValuationCell.vue'
import {
  formatConnectionValuation, formatMoneyWithCurrency, formatSignedMoney, moneyTone
} from 'src/services/money'

const toast = inject('toast')

// Живость стримов
const streams = ref({ supported: false, marketData: null, orders: null })

const streamRows = computed(() => {
  const rows = [
    { key: 'marketData', title: 'Рыночные данные', health: streams.value.marketData },
    { key: 'orders', title: 'Состояния ордеров', health: streams.value.orders }
  ]
  return rows.map(r => ({
    key: r.key,
    title: r.title,
    connected: !!(r.health && r.health.connected),
    reconnects: (r.health && r.health.reconnectCount) || 0,
    error: r.health && r.health.lastError,
    lastEvent: r.health && r.health.lastEventAt ? formatInstant(r.health.lastEventAt) : 'не было'
  }))
})

async function loadStreams (id) {
  if (!id) {
    streams.value = { supported: false, marketData: null, orders: null }
    return
  }
  try {
    streams.value = await apiClient.get(`/api/v1/exchange-connections/${id}/streams`)
  } catch (e) {
    console.debug(e)
  }
}

// Счёт и параметры тарифа
const accounts = ref([])
/** Подключение, которому принадлежит загруженный список счетов. */
const accountsOwnerId = ref(null)
const accountsLoading = ref(false)
const settingsSaving = ref(false)
const settingsForm = reactive({ accountId: null, commissionPercent: 0.3, baseCurrency: '', marginEnabled: false })

const accountOptions = computed(() => {
  const options = accounts.value.map(a => ({
    value: a.id,
    label: `${a.name || 'Без названия'} · ${a.id}${a.sandbox ? ' (песочница)' : ''}`
  }))

  // Уже сохранённый счёт показываем всегда, даже если список с биржи ещё не
  // загружен: иначе поле выглядит пустым, и кажется, что счёт слетел.
  const saved = settingsForm.accountId
  if (saved && !options.some(o => o.value === saved)) {
    options.unshift({ value: saved, label: `${saved} · сохранённый` })
  }
  return options
})

const roundTripPercent = computed(() => {
  const rate = Number(settingsForm.commissionPercent) || 0
  return (rate * 2).toFixed(3)
})

async function loadAccounts (id) {
  if (!id) {
    accounts.value = []
    accountsOwnerId.value = null
    return
  }

  // Список принадлежит конкретному подключению. Показать счета одной биржи в форме
  // другой — худшее, что тут может случиться: выбранный счёт ушёл бы в чужие настройки.
  if (accountsOwnerId.value !== id) {
    accounts.value = []
    accountsOwnerId.value = id
  }

  accountsLoading.value = true
  try {
    accounts.value = await apiClient.get(`/api/v1/exchange-connections/${id}/accounts`)
  } catch (e) {
    // Подключение может быть не активно — это ожидаемо, не шумим.
    //
    // Ранее загруженный список ТОГО ЖЕ подключения сохраняем. Счета отдаёт только
    // поднятое подключение, а менять его параметры можно только на остановленном:
    // очистка на ошибке означала бы, что в момент, когда счёт разрешено выбрать,
    // выбирать уже не из чего.
    console.debug(e)
  } finally {
    accountsLoading.value = false
  }
}

async function saveSettings () {
  const id = detail.value?.id
  if (!id) return

  settingsSaving.value = true
  try {
    // В UI проценты, в API — доля: так понятнее человеку и однозначнее машине.
    const rate = (Number(settingsForm.commissionPercent) || 0) / 100
    await apiClient.patch(`/api/v1/exchange-connections/${id}/settings`, {
      id,
      accountId: settingsForm.accountId,
      settings: {
        ...(detail.value.settings || {}),
        commissionRate: rate,
        // Пустая строка означает «не задано»: пусть решает биржа, а не пустой код валюты.
        baseCurrency: (settingsForm.baseCurrency || '').trim().toUpperCase() || null,
        marginEnabled: settingsForm.marginEnabled === true
      }
    })
    toast?.ok('Успешно сохранено')
    await loadDetail(id)
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось сохранить'))
  } finally {
    settingsSaving.value = false
  }
}

// Боты выбранного подключения — чтобы перед остановкой было видно, кого это заденет.
const connectionBots = ref([])
const runningBotsCount = computed(
  () => connectionBots.value.filter(b => isRuntimeActive(b.runtime && b.runtime.state)).length
)

async function loadConnectionBots (id) {
  if (!id) {
    connectionBots.value = []
    return
  }
  try {
    connectionBots.value = await apiClient.get(`/api/v1/exchange-connections/${id}/bots`)
  } catch (e) {
    connectionBots.value = []
    console.debug(e)
  }
}

const list = ref([])
const listLoading = ref(false)

const selectedId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)

const connectionMoney = computed(() =>
  formatConnectionValuation(detail.value && detail.value.valuation))

// runtime status (in-memory state on backend)
const runtime = ref(null)
const runtimeLoading = ref(false)
const actionLoading = ref(false)
const catalogSyncing = ref(false)
const desiredActive = ref(null) // true/false while we wait runtime to reach target

let runtimePollTimer = null
let runtimeErrorNotified = false

function clearRuntimePoll () {
  if (runtimePollTimer) {
    clearInterval(runtimePollTimer)
    runtimePollTimer = null
  }
  runtimeErrorNotified = false
}


async function loadRuntime (id) {
  if (!id) return
  runtimeLoading.value = true
  try {
    runtime.value = await apiClient.get(`/api/v1/exchange-connections/${id}/runtime`)
    // Статусы ботов и стримов меняются вместе со статусом подключения — тянем тем же тиком.
    await loadConnectionBots(id)
    await loadStreams(id)
  } finally {
    runtimeLoading.value = false
  }
}

async function waitRuntimeToTarget (id, targetActive) {
  desiredActive.value = targetActive
  clearRuntimePoll()

  // immediate refresh
  await loadRuntime(id)

  const startedAt = Date.now()
  const timeoutMs = 60_000
  const periodMs = 1500

  runtimePollTimer = setInterval(async () => {
    try {
      await loadRuntime(id)

      // if runtime reports an error, notify once (and stop waiting)
      const errText = runtime.value?.lastError
      if (errText && !runtimeErrorNotified) {
        runtimeErrorNotified = true
        clearRuntimePoll()
        desiredActive.value = null
        toast?.err(errText, { timeout: 6000 })
        return
      }

      const st = runtime.value?.state
      const ok = targetActive ? isRuntimeActive(st) : isRuntimeInactive(st)
      if (ok) {
        clearRuntimePoll()
        desiredActive.value = null
        toast?.ok(targetActive ? 'Биржа успешно запущена' : 'Биржа успешно остановлена')
        return
      }

      if (Date.now() - startedAt > timeoutMs) {
        clearRuntimePoll()
        desiredActive.value = null
        toast?.warn('Не дождались подтверждения статуса в runtime (таймаут)')
      }
    } catch (e) {
      console.log(e)
      // keep polling (avoid spamming)
    }
  }, periodMs)
}

/**
 * Явный выход из состояния «справочник пуст»: синхронизация сама срабатывает при запуске
 * подключения и по расписанию, но без кнопки пользователю оставалось бы только гадать,
 * почему в форме бота ничего не находится.
 */
async function syncCatalog () {
  const exchange = detail.value?.exchange
  if (!exchange) return

  catalogSyncing.value = true
  try {
    await apiClient.post(`/api/v1/instruments/sync?exchange=${encodeURIComponent(exchange)}`)
    toast?.info('Обновление справочника запущено — результат придёт в Telegram')
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось обновить справочник'))
  } finally {
    catalogSyncing.value = false
  }
}

async function doActivateDeactivate () {
  const id = detail.value?.id
  if (!id) return

  const targetActive = !detail.value.active

  actionLoading.value = true
  try {
    if (targetActive) {
      await apiClient.post(`/api/v1/exchange-connections/${id}/activate`)
      toast?.info('Команда запуска отправлена')
    } else {
      await apiClient.post(`/api/v1/exchange-connections/${id}/deactivate`)
      toast?.info('Команда остановки отправлена')
    }

    // refresh detail (DB target flag might change) and wait for runtime to converge
    await loadDetail(id)
    await waitRuntimeToTarget(id, targetActive)

    // refresh list badges
    await loadList()
  } catch (e) {
    // try to show server-provided error text if possible
    toast?.err(getErrorMessage(e, 'Не удалось выполнить действие'))
  } finally {
    actionLoading.value = false
  }
}

const confirmDelete = ref(false)
const deleteLoading = ref(false)

/** create */
const createDialog = ref(false)
const createLoading = ref(false)
const typesLoading = ref(false)
const exchangeTypes = ref([]) // ["T_INVEST", ...]
const createForm = reactive({
  exchange: null,
  name: ''
})

const canCreate = computed(() => {
  const ex = createForm.exchange
  const name = (createForm.name || '').trim()
  return !!ex && name.length > 0 && !createLoading.value
})

// Inline name edit
const editName = ref('')
const originalName = ref('')
const nameSaving = ref(false)

// Sandbox toggle
/**
 * Есть ли у биржи песочница.
 *
 * Список короткий и живёт на фронте намеренно: это свойство биржи, а не подключения,
 * и запрашивать его отдельным вызовом ради одного переключателя не стоит.
 */
const EXCHANGES_WITH_SANDBOX = ['T_INVEST']
const supportsSandbox = computed(() =>
  EXCHANGES_WITH_SANDBOX.includes(detail.value?.exchange))

/**
 * Биржи, где тариф задаёт пользователь, а не отдаёт API.
 *
 * У T-Invest ставка не приходит дёшево, поэтому её вводят руками. Криптобиржи
 * отдают её сами и зависят от оборота, а введённое руками число там просто
 * игнорируется — показывать поле значило бы обещать влияние, которого нет.
 */
const EXCHANGES_WITH_MANUAL_FEE = ['T_INVEST']
const usesManualFee = computed(() =>
  EXCHANGES_WITH_MANUAL_FEE.includes(detail.value?.exchange))

const sandboxEnabled = ref(false)
const sandboxSaving = ref(false)

// Credentials dialog
const credentialsDialog = ref(false)
const credentialsSaving = ref(false)
const credentialsForm = reactive({
  apiKey: '',
  apiSecret: '',
  passphrase: ''
})

const isNameDirty = computed(() => {
  const a = (editName.value || '').trim()
  const b = (originalName.value || '').trim()
  return a !== b
})

onMounted(async () => {
  await Promise.all([
    loadTypes(),
    loadList()
  ])
})

onBeforeUnmount(() => {
  clearRuntimePoll()
})

async function loadList () {
  listLoading.value = true
  try {
    const data = await apiClient.get('/api/v1/exchange-connections', {
      params: { page: 0, size: 100, sort: 'createdAt,desc' }
    })

    list.value = Array.isArray(data?.content) ? data.content : []

    if (selectedId.value && !list.value.some(x => x.id === selectedId.value)) {
      selectedId.value = null
      detail.value = null
    }

    if (!selectedId.value && list.value.length > 0) {
      await select(list.value[0].id)
    }
  } finally {
    listLoading.value = false
  }
}

async function select (id) {
  if (!id || selectedId.value === id) return
  selectedId.value = id
  desiredActive.value = null
  clearRuntimePoll()
  // Reset inline edit/sandbox state
  editName.value = ''
  originalName.value = ''
  sandboxEnabled.value = false
  await Promise.all([
    loadDetail(id),
    loadRuntime(id),
    loadConnectionBots(id)
  ])
}

async function loadDetail (id) {
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await apiClient.get(`/api/v1/exchange-connections/${id}`)
    // Sync inline name edit and sandbox toggle state
    originalName.value = detail.value?.name || ''
    editName.value = originalName.value
    sandboxEnabled.value = !!detail.value?.sandboxEnabled

    settingsForm.accountId = detail.value?.accountId || null
    // В API доля, в UI проценты.
    settingsForm.commissionPercent = Number(
      ((detail.value?.settings?.commissionRate ?? 0.003) * 100).toFixed(4)
    )
    settingsForm.baseCurrency = detail.value?.settings?.baseCurrency || ''
    settingsForm.marginEnabled = detail.value?.settings?.marginEnabled === true

    if (selectedId.value === id) {
      await loadRuntime(id)
      await loadAccounts(id)
    }
  } finally {
    detailLoading.value = false
  }
}


async function doDelete () {
  if (!detail.value?.id) return
  deleteLoading.value = true
  try {
    await apiClient.delete(`/api/v1/exchange-connections/${detail.value.id}`)
    confirmDelete.value = false

    clearRuntimePoll()
    desiredActive.value = null
    runtime.value = null

    selectedId.value = null
    detail.value = null
    await loadList()
  } finally {
    deleteLoading.value = false
  }
}

async function openCreate () {
  createForm.exchange = null
  createForm.name = ''
  createDialog.value = true

  if (exchangeTypes.value.length === 0) {
    await loadTypes()
  }
}

async function loadTypes () {
  typesLoading.value = true
  try {
    const data = await apiClient.get('/api/v1/exchange-connections/types')
    exchangeTypes.value = Array.isArray(data) ? data : []
  } finally {
    typesLoading.value = false
  }
}

async function doCreate () {
  if (!canCreate.value) return

  createLoading.value = true
  try {
    const payload = {
      exchange: createForm.exchange,
      name: createForm.name.trim()
    }

    const id = await apiClient.post('/api/v1/exchange-connections', payload)

    createDialog.value = false

    // 1) перезагрузить список
    await loadList()

    // 2) выбрать созданную запись (и подгрузить детали)
    await select(id)

  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось создать подключение'))
  } finally {
    createLoading.value = false
  }
}

// Inline name save
async function saveName () {
  const id = detail.value?.id
  if (!id) return

  nameSaving.value = true
  try {
    const payload = {
      id,
      name: (editName.value || '').trim()
    }

    await apiClient.patch(`/api/v1/exchange-connections/${id}/name`, payload)
    toast?.ok('Успешно сохранено')

    // refresh detail + list
    await loadDetail(id)
    await loadList()
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось сохранить'))
  } finally {
    nameSaving.value = false
  }
}

async function onSandboxToggle (val) {
  const id = detail.value?.id
  if (!id) return

  const prev = !val
  sandboxSaving.value = true
  try {
    const payload = { id, enabled: !!val }
    await apiClient.patch(`/api/v1/exchange-connections/${id}/sandbox`, payload)
    toast?.ok('Успешно сохранено')

    await loadDetail(id)
    await loadList()
  } catch (e) {
    // revert UI toggle
    sandboxEnabled.value = prev
    toast?.err(getErrorMessage(e, 'Не удалось сохранить'))
  } finally {
    sandboxSaving.value = false
  }
}

function openCredentialsDialog () {
  credentialsForm.apiKey = ''
  credentialsForm.apiSecret = ''
  credentialsForm.passphrase = ''
  credentialsDialog.value = true
}

async function saveCredentials () {
  const id = detail.value?.id
  if (!id) return

  credentialsSaving.value = true
  try {
    const payload = {
      id,
      apiKey: credentialsForm.apiKey,
      apiSecret: credentialsForm.apiSecret,
      passphrase: credentialsForm.passphrase
    }

    await apiClient.put(`/api/v1/exchange-connections/${id}/credentials`, payload)
    toast?.ok('Успешно сохранено')

    credentialsDialog.value = false
    await loadDetail(id)
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось сохранить'))
  } finally {
    credentialsSaving.value = false
  }
}



function formatInstant (instant) {
  if (!instant) return '—'
  const d = new Date(instant)
  if (Number.isNaN(d.getTime())) return instant
  return d.toLocaleString()
}
</script>

<style scoped>
.page-root {
  height: 100%;
}

.main-row {
  height: calc(100vh - 120px);
  display: flex;
  gap: 12px;
}

.left-pane {
  width: 20%;
  min-width: 220px;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--q-color-grey-4);
  border-radius: 8px;
  overflow: hidden;
}

.right-pane {
  width: 80%;
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

.money-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}

.money-label {
  color: #757575;
  font-size: 12px;
  line-height: 1.2;
}

.money-value {
  margin-top: 4px;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.2;
}
</style>
