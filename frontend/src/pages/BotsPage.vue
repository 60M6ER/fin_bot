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
                <div class="row items-center q-gutter-sm no-wrap">
                  <bot-valuation-cell :valuation="item.valuation" />
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

            <!--
              Плановая остановка стоит рядом с обычной намеренно: это две ветки
              одного решения. Обычная гасит бота там, где он есть, вместе с купленным;
              плановая доводит циклы до конца и выключает бота сам.
            -->
            <q-btn
              v-if="detail && detail.active && stopScheduled"
              dense
              outline
              color="primary"
              icon="undo"
              label="Отменить остановку"
              :loading="scheduleStopLoading"
              :disable="detailLoading || scheduleStopLoading"
              @click="doCancelScheduledStop"
            />
            <q-btn
              v-else-if="detail && detail.active"
              dense
              outline
              color="orange-9"
              icon="hourglass_bottom"
              label="Запланировать остановку"
              :loading="scheduleStopLoading"
              :disable="detailLoading || scheduleStopLoading"
              @click="scheduleStopDialog = true"
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
                      :exchange="selectedExchange"
                      :connection-id="editForm.exchangeConnectionId"
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
                    <div class="metric-label">Баланс бота</div>
                    <div class="metric-value mono">
                      {{ formatMoneyWithCurrency(accounting.equity, accounting.currency) }}
                    </div>
                    <div class="metric-hint">
                      <template v-if="accounting.equity === null || accounting.equity === undefined">
                        {{ accounting.budget === null || accounting.budget === undefined
                          ? 'бюджет не задан' : 'нет актуальной цены' }}
                      </template>
                      <template v-else>
                        бюджет {{ formatMoney(accounting.workingBudget) }}
                      </template>
                    </div>
                  </div>
                  <div class="metric-tile">
                    <div class="metric-label">Общий P/L</div>
                    <div class="metric-value" :class="moneyTone(accounting.totalPnl)">
                      {{ formatSignedMoney(accounting.totalPnl, accounting.currency) }}
                    </div>
                    <div class="metric-hint">реализованный + нереализованный</div>
                  </div>
                  <div class="metric-tile">
                    <div class="metric-label">Реализованный P/L</div>
                    <div class="metric-value" :class="moneyTone(accounting.realizedPnl)">
                      {{ formatSignedMoney(accounting.realizedPnl, accounting.currency) }}
                    </div>
                  </div>
                  <div class="metric-tile">
                    <div class="metric-label">Нереализованный P/L</div>
                    <div class="metric-value" :class="moneyTone(accounting.unrealizedPnl)">
                      {{ formatSignedMoney(accounting.unrealizedPnl, accounting.currency) }}
                    </div>
                  </div>
                  <div class="metric-tile">
                    <div class="metric-label">Рыночная стоимость</div>
                    <div class="metric-value mono">
                      {{ formatMoneyWithCurrency(accounting.marketValue, accounting.currency) }}
                    </div>
                    <div class="metric-hint">
                      <template v-if="accounting.lastPrice !== null && accounting.lastPrice !== undefined">
                        по {{ formatMoney(accounting.lastPrice) }} от
                        {{ formatMoneyTime(accounting.lastPriceAt) }}
                      </template>
                      <template v-else>цена из потока не получена</template>
                    </div>
                  </div>
                  <div
                    v-if="accounting.profitPolicy === 'WITHDRAW'"
                    class="metric-tile"
                  >
                    <div class="metric-label">Выведено прибыли</div>
                    <div class="metric-value" :class="moneyTone(accounting.withdrawnProfit)">
                      {{ formatSignedMoney(accounting.withdrawnProfit, accounting.currency) }}
                    </div>
                    <div class="metric-hint">не реинвестируется</div>
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
                    <div class="metric-label">Открыто</div>
                    <div class="metric-value mono">{{ formatQuantity(accounting.openQuantity) }}</div>
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
                    <td class="text-right mono">{{ formatQuantity(row.quantity) }}</td>
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

            <!--
              Успешность каждого диапазона по отдельности. Сводная доходность выше
              этого не показывает: она не различает, заработал бот на удачной сетке
              или отбил ею убыток предыдущей перестановки.
            -->
            <q-card flat bordered class="q-mt-md">
              <q-card-section class="row items-center justify-between">
                <div class="text-subtitle2">Поколения сетки</div>
                <div class="text-caption text-grey">
                  итог = стоимость перехода + P/L циклов
                </div>
              </q-card-section>

              <q-separator />

              <q-markup-table flat dense wrap-cells class="generations-table">
                <thead>
                  <tr>
                    <th class="text-left">Поколение</th>
                    <th class="text-left">Диапазон</th>
                    <th class="text-right">Циклов</th>
                    <th class="text-right">Стоимость перехода</th>
                    <th class="text-right">P/L циклов</th>
                    <th class="text-right">Итого</th>
                    <th class="text-left">Период</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="g in generations" :key="g.generation">
                    <td>
                      <span class="mono">#{{ g.generation }}</span>
                      <q-badge
                        v-if="g.active"
                        color="primary"
                        label="действующее"
                        outline
                        class="q-ml-xs"
                      />
                    </td>
                    <td class="mono">
                      {{ formatMoney(g.lowerPrice) }} — {{ formatMoney(g.upperPrice) }}
                      <div class="text-caption text-grey">{{ rangeOriginLabel(g.origin) }}</div>
                    </td>
                    <td class="text-right mono">{{ g.cycles }}</td>
                    <td class="text-right mono" :class="moneyTone(g.transitionCost)">
                      {{ formatSignedMoney(g.transitionCost, g.currency) }}
                      <q-tooltip v-if="Number(g.transitionCost) !== 0">
                        Убыток, зафиксированный принудительным закрытием позиции
                        перед этим диапазоном
                      </q-tooltip>
                    </td>
                    <td class="text-right mono" :class="moneyTone(g.cyclesPnl)">
                      {{ formatSignedMoney(g.cyclesPnl, g.currency) }}
                    </td>
                    <td class="text-right mono text-weight-medium" :class="moneyTone(g.totalPnl)">
                      {{ formatSignedMoney(g.totalPnl, g.currency) }}
                    </td>
                    <td class="mono text-caption">
                      {{ formatInstant(g.startedAt) }}
                      <div class="text-grey">
                        {{ g.endedAt ? formatInstant(g.endedAt) : 'по настоящее время' }}
                      </div>
                    </td>
                  </tr>
                  <tr v-if="generations.length === 0">
                    <td colspan="7" class="text-grey">
                      Поколений пока нет — бот ещё не строил сетку
                    </td>
                  </tr>
                </tbody>
              </q-markup-table>

              <q-card-section class="text-caption text-grey">
                P/L циклов — только закрытые циклы: открытая позиция действующего
                поколения в него не входит.
              </q-card-section>
            </q-card>

            <!-- Что бот делает прямо сейчас -->
            <q-card flat bordered class="q-mt-md">
              <q-card-section class="row items-center justify-between">
                <div class="text-subtitle2">
                  Состояние
                  <q-badge v-if="state.dryRun" color="purple" label="бумажный режим" outline class="q-ml-sm" />
                  <!--
                    Выход из штатного тупика: бюджет убытка исчерпан, цена ниже всего
                    диапазона — купить нельзя, продать нельзя, сам бот не выберется.
                  -->
                  <q-btn
                    v-if="canForceReplace"
                    dense
                    outline
                    no-caps
                    size="sm"
                    color="deep-orange"
                    icon="restart_alt"
                    label="Перестроить сетку с фиксацией убытка"
                    class="q-ml-md"
                    :loading="forceLoading"
                    @click="forceDialog = true"
                  />
                </div>
                <div class="row items-center q-gutter-md text-caption">
                  <div>Позиция: <b class="mono">{{ formatQuantity(state.position) }}</b></div>
                  <div>Занято: <b class="mono">{{ formatMoney(state.reservedByBuyOrders) }}</b></div>
                  <div>
                    Активных: <b class="mono">{{ state.openOrdersCount }}</b>
                  </div>
                  <div v-if="state.queueSize > 0" class="text-orange-9">
                    Очередь: {{ state.queueSize }}
                  </div>
                </div>
              </q-card-section>

              <template v-if="state.strategySnapshot">
                <q-separator />
                <q-card-section class="runtime-range">
                  <div class="row items-center q-gutter-sm">
                    <span class="text-caption text-grey-7">Действующий диапазон</span>
                    <b class="mono">{{ formatMoney(state.strategySnapshot.lowerPrice) }} — {{ formatMoney(state.strategySnapshot.upperPrice) }}</b>
                    <q-badge :label="rangeOriginLabel(state.strategySnapshot.rangeOrigin)" color="primary" outline />
                    <q-badge
                      v-if="state.strategySnapshot.awaitingReplacement"
                      :label="state.strategySnapshot.replacementDirection === 'DOWN'
                        ? 'принудительное закрытие вниз'
                        : 'закрытие перед перестановкой вверх'"
                      color="orange-8" outline
                    />
                    <q-badge
                      v-if="state.strategySnapshot.stopScheduled"
                      label="плановая остановка: жду распродажи"
                      color="orange-9" outline
                    />
                    <q-badge v-if="state.strategySnapshot.buyingStopped" label="покупки остановлены" color="warning" outline />
                    <q-badge v-if="state.strategySnapshot.halted" label="остановлена" color="negative" outline />
                  </div>
                  <div class="text-caption text-grey-7 q-mt-xs">
                    Поколение {{ state.strategySnapshot.generation }} · с {{ formatInstant(state.strategySnapshot.rangeSince) }}
                    <template v-if="state.strategySnapshot.downwardReplacements > 0">
                      · вниз {{ state.strategySnapshot.downwardReplacements }}
                      · израсходовано бюджета убытка
                      {{ formatMoney(state.strategySnapshot.realizedDownwardLoss) }}
                      <q-tooltip>
                        Счётчик риск-бюджета, а не результат бота: он обнуляется при
                        ручной перестройке сетки. Фактический убыток остаётся в P/L
                        и в статистике поколений.
                      </q-tooltip>
                    </template>
                  </div>
                  <div
                    v-if="state.strategySnapshot.workingBudget !== null
                      && state.strategySnapshot.workingBudget !== undefined"
                    class="text-caption text-grey-7"
                  >
                    Бюджет {{ formatMoney(state.strategySnapshot.workingBudget) }} ·
                    задействовано {{ formatMoney(state.strategySnapshot.worstCaseNotional) }}
                    <template v-if="state.strategySnapshot.sizingMode">
                      · {{ sizingModeLabel(state.strategySnapshot.sizingMode) }}
                    </template>
                  </div>
                  <!--
                    Единственная настройка, которую можно менять под работающим ботом:
                    общая форма требует остановки, а остановка снимает встречные
                    продажи незакрытых циклов.

                    Вводится ПРИБАВКА, а не итоговый бюджет: пополнение и вывод — это
                    «сколько денег принесли или забрали», и считать разницу в уме
                    оператор не должен. Складывает сервер — рядом показан рабочий
                    бюджет, а он у бота с реинвестированием включает прибыль.
                  -->
                  <div class="row items-center q-gutter-sm">
                    <q-input
                      v-model.number="budgetDelta"
                      type="number"
                      outlined
                      dense
                      style="max-width: 160px"
                      label="Сумма"
                      :disable="budgetLoading"
                      @keyup.enter="changeBudget(1)"
                    />
                    <q-btn
                      dense
                      unelevated
                      no-caps
                      size="sm"
                      color="primary"
                      icon="add"
                      label="Добавить к бюджету"
                      :loading="budgetLoading"
                      :disable="!budgetDelta"
                      @click="changeBudget(1)"
                    />
                    <q-btn
                      dense
                      outline
                      no-caps
                      size="sm"
                      color="primary"
                      icon="remove"
                      label="Вывести"
                      :disable="budgetLoading || !budgetDelta"
                      @click="changeBudget(-1)"
                    />
                    <div v-if="budgetResult" class="text-caption text-grey-7">
                      свободно {{ formatMoney(budgetResult.free) }} ·
                      занято {{ formatMoney(budgetResult.committed) }},
                      из них позиция {{ formatMoney(budgetResult.positionCost) }}
                    </div>
                  </div>
                </q-card-section>
              </template>

              <q-separator />

              <q-card-section>
                <grid-state-board
                  :snapshot="state.strategySnapshot"
                  :orders="state.orders"
                />
              </q-card-section>
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

    <!-- Плановая остановка -->
    <q-dialog v-model="scheduleStopDialog">
      <q-card style="min-width: 520px; max-width: 640px">
        <q-card-section class="row items-center q-gutter-sm">
          <q-icon name="hourglass_bottom" color="orange-9" size="md" />
          <div class="text-subtitle1">Запланировать остановку?</div>
        </q-card-section>

        <q-card-section class="text-body2">
          Покупки снимутся сразу, встречные продажи останутся работать. Бот выключится
          сам, когда позиция закроется, — после этого его можно безопасно удалить.
          <div class="text-caption text-grey q-mt-sm">
            Продажи стоят по ценам сетки, а не по рынку, поэтому ждать можно долго:
            это размен цены на скорость. Нужно закрыться быстро — перестройте сетку
            с фиксацией убытка, она продаёт по рынку. Решение отменяемо.
          </div>
        </q-card-section>

        <q-card-actions align="right">
          <q-btn flat label="Отмена" v-close-popup :disable="scheduleStopLoading" />
          <q-btn
            unelevated
            color="orange-9"
            label="Запланировать"
            :loading="scheduleStopLoading"
            @click="doScheduleStop"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- Перестройка сетки с фиксацией убытка -->
    <q-dialog v-model="forceDialog">
      <q-card style="min-width: 520px; max-width: 640px">
        <q-card-section class="row items-center q-gutter-sm">
          <q-icon name="restart_alt" color="deep-orange" size="md" />
          <div class="text-subtitle1">Перестроить сетку с фиксацией убытка?</div>
        </q-card-section>

        <q-card-section class="q-pt-none">
          <div class="q-mb-sm">Бот сделает это по порядку:</div>
          <ol class="force-steps">
            <li>снимет все свои заявки;</li>
            <li>
              продаст позицию
              <b class="mono">{{ formatQuantity(state.position) }}</b>
              одной заявкой по лучшему биду — <b>по рынку, цена не гарантируется</b>;
            </li>
            <li>зафиксирует убыток: он останется в P/L и в статистике поколений;</li>
            <li>построит новую сетку вокруг текущей цены и продолжит работу.</li>
          </ol>

          <q-banner dense class="bg-orange-1 text-orange-10 q-mt-md" rounded>
            Потолок убытка и лимит перестановок для этой операции снимаются, а счётчик
            риск-бюджета обнуляется — следующие автоматические перестановки начнут
            считать его с нуля. На результативность бота это не влияет.
          </q-banner>

          <div v-if="currentRange" class="text-caption text-grey q-mt-md">
            Текущий диапазон: <span class="mono">{{ currentRange }}</span>
          </div>
          <div class="text-caption text-grey q-mt-xs">Действие необратимо.</div>
        </q-card-section>

        <q-card-actions align="right" class="q-pa-md">
          <q-btn flat label="Отмена" v-close-popup :disable="forceLoading" />
          <q-btn
            color="deep-orange"
            label="Перестроить"
            :loading="forceLoading"
            @click="doForceReplace"
          />
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
import BotValuationCell from 'components/BotValuationCell.vue'
import GridStateBoard from 'components/GridStateBoard.vue'
import {
  formatMoney, formatMoneyWithCurrency, formatFeeWithCurrency,
  formatSignedMoney, moneyTone, formatTime as formatMoneyTime
} from 'src/services/money'

const toast = inject('toast')

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
const forceDialog = ref(false)
const forceLoading = ref(false)
const scheduleStopDialog = ref(false)
const scheduleStopLoading = ref(false)
const budgetLoading = ref(false)
const budgetDelta = ref(null)
const budgetResult = ref(null)

const stopScheduled = computed(() => !!state.value.strategySnapshot?.stopScheduled)
const createForm = reactive({ name: '', strategyType: null, exchangeConnectionId: null })

const editForm = reactive({ name: '', strategyType: null, exchangeConnectionId: null, strategyConfig: '{}' })
let editOriginal = {}

let pollTimer = null

const connectionOptions = computed(() =>
  connections.value.map(c => ({ value: c.id, label: `${c.name} (${c.exchange})` }))
)

const selectedExchange = computed(() =>
  connections.value.find(c => c.id === editForm.exchangeConnectionId)?.exchange || null
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
  running: false, dryRun: false, position: 0,
  reservedByBuyOrders: null, openOrdersCount: 0, queueSize: 0,
  strategySnapshot: null, orders: []
})
const botEvents = ref([])
const eventLevelFilter = ref('все')
const accountingLoading = ref(false)
const accounting = ref(emptyAccounting())
const ledger = ref([])
const generations = ref([])

const filteredEvents = computed(() =>
  eventLevelFilter.value === 'все'
    ? botEvents.value
    : botEvents.value.filter(e => e.level === eventLevelFilter.value)
)

const ledgerRows = computed(() => ledger.value.slice(0, 50))

const SIZING_MODE_LABELS = {
  FIXED_LOTS: 'фиксировано лотов',
  UNIFORM: 'один размер на все уровни',
  PER_LEVEL: 'поровну денег на уровень'
}

function sizingModeLabel (mode) {
  return SIZING_MODE_LABELS[mode] || mode
}

function emptyAccounting () {
  return {
    dryRun: false,
    cashFlow: 0,
    costBasisOpen: 0,
    realizedPnl: 0,
    paidCommission: 0,
    openQuantity: 0,
    averageEntryPrice: null,
    currency: null,
    lastPrice: null,
    lastPriceAt: null,
    marketValue: null,
    unrealizedPnl: null,
    totalPnl: null,
    budget: null,
    workingBudget: null,
    withdrawnProfit: 0,
    equity: null,
    profitPolicy: null,
    sizingMode: null
  }
}

/**
 * Количество: до восьми значащих знаков, без группировки.
 *
 * Округлять нельзя — 0.000001 BTC превратилось бы в ноль, и экран сообщал бы,
 * что позиции нет, хотя она есть.
 */
function formatQuantity (value) {
  if (value === null || value === undefined) return '—'
  const number = Number(value)
  if (Number.isNaN(number)) return String(value)
  return number.toLocaleString('ru-RU', { maximumFractionDigits: 8, useGrouping: false })
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

function rangeOriginLabel (origin) {
  switch (origin) {
    case 'MANUAL': return 'ручной'
    case 'ATR_INITIAL': return 'ATR'
    case 'ATR_REPLACED_UP': return 'ATR · выше'
    case 'ATR_REPLACED_DOWN': return 'ATR · ниже'
    default: return origin || '—'
  }
}

async function loadState (id) {
  if (!id) return
  try {
    const [s, ev, acc, led, gen] = await Promise.all([
      apiClient.get(`/api/v1/bots/${id}/state`),
      apiClient.get(`/api/v1/bots/${id}/events`, { params: { limit: 100 } }),
      apiClient.get(`/api/v1/bots/${id}/accounting`),
      apiClient.get(`/api/v1/bots/${id}/ledger`),
      apiClient.get(`/api/v1/bots/${id}/generations`)
    ])
    state.value = s
    botEvents.value = Array.isArray(ev) ? ev : []
    accounting.value = acc || emptyAccounting()
    ledger.value = Array.isArray(led) ? led : []
    generations.value = Array.isArray(gen) ? gen : []
  } catch (e) {
    console.debug(e)
  }
}

async function loadAccounting (id) {
  if (!id) return
  accountingLoading.value = true
  try {
    const [acc, led, gen] = await Promise.all([
      apiClient.get(`/api/v1/bots/${id}/accounting`),
      apiClient.get(`/api/v1/bots/${id}/ledger`),
      apiClient.get(`/api/v1/bots/${id}/generations`)
    ])
    accounting.value = acc || emptyAccounting()
    ledger.value = Array.isArray(led) ? led : []
    generations.value = Array.isArray(gen) ? gen : []
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

/**
 * Команда исполняется стратегией на потоке её событийного цикла, поэтому
 * остановленному боту её отдавать некому — кнопку показываем только работающему.
 */
const canForceReplace = computed(() =>
  !!detail.value
  && detail.value.strategyType === 'GRID'
  && !!state.value.running
)

const currentRange = computed(() => {
  const s = state.value.strategySnapshot
  if (!s) return null
  return `${formatMoney(s.lowerPrice)} — ${formatMoney(s.upperPrice)}`
})

/**
 * Отправляем ПРИБАВКУ (со знаком), а не итоговую сумму: складывает сервер, потому что
 * рядом на экране показан рабочий бюджет — у бота с реинвестированием это «бюджет плюс
 * прибыль», и сложение на клиенте вшило бы заработанное в базовый бюджет.
 *
 * @param sign 1 — добавить, -1 — вывести
 */
async function changeBudget (sign) {
  const id = detail.value?.id
  const amount = Number(budgetDelta.value)
  if (!id || !amount) return

  budgetLoading.value = true
  try {
    const { data } = await apiClient.post(`/api/v1/bots/${id}/budget`, {
      addToBudget: sign * Math.abs(amount)
    })
    budgetResult.value = data
    budgetDelta.value = null
    toast?.ok(`${sign > 0 ? 'Бюджет пополнен' : 'Бюджет уменьшен'}: `
      + `${formatMoney(data.previousBudget)} → ${formatMoney(data.budget)}`
      + (data.appliedLive ? '' : ' (подействует при следующем запуске)'))
    await loadState(id)
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось изменить бюджет'), { timeout: 8000 })
  } finally {
    budgetLoading.value = false
  }
}

async function doScheduleStop () {
  await sendStopCommand('schedule-stop', 'Остановка запланирована — жду распродажи позиции')
  scheduleStopDialog.value = false
}

async function doCancelScheduledStop () {
  await sendStopCommand('cancel-scheduled-stop', 'Плановая остановка отменена')
}

async function sendStopCommand (path, okMessage) {
  const id = detail.value?.id
  if (!id) return
  scheduleStopLoading.value = true
  try {
    await apiClient.post(`/api/v1/bots/${id}/${path}`)
    // Ответ означает «команда принята»: работу бот делает у себя в цикле.
    toast?.ok(okMessage)
    await loadState(id)
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось выполнить команду'), { timeout: 8000 })
  } finally {
    scheduleStopLoading.value = false
  }
}

async function doForceReplace () {
  const id = detail.value?.id
  if (!id) return
  forceLoading.value = true
  try {
    await apiClient.post(`/api/v1/bots/${id}/force-grid-replacement`)
    forceDialog.value = false
    // Ответ означает только «команда принята»: работу бот делает у себя в цикле,
    // и о результате сообщит событиями. Их и подтянем.
    toast?.ok('Команда принята — смотрите события бота')
    await loadState(id)
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось перестроить сетку'), { timeout: 8000 })
  } finally {
    forceLoading.value = false
  }
}

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

.runtime-range {
  padding-top: 10px;
  padding-bottom: 10px;
}

.runtime-ladder {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  font-size: 12px;
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

.metric-hint {
  margin-top: 4px;
  color: #9e9e9e;
  font-size: 11px;
  line-height: 1.2;
}

.ledger-table {
  max-height: 320px;
  overflow: auto;
}

.generations-table {
  max-height: 320px;
  overflow: auto;
}

.force-steps {
  margin: 0;
  padding-left: 20px;
  line-height: 1.7;
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
