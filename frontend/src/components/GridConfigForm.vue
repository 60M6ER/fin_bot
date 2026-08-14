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
      Полный выкуп: {{ formatDecimal(preview.worstCaseCapital, 2) }} при шаге количества {{ formatQuantity(preview.quantityStep) }}.
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
        <field-hint :text="hints.instrumentUid" />
      </div>
      <div class="col-12 col-md-4 flex items-center">
        <q-toggle v-model="model.autoRange" label="Границы по волатильности" :disable="disable">
          <field-hint :text="hints.autoRange" />
        </q-toggle>
      </div>

      <template v-if="!model.autoRange">
        <div class="col-6 col-md-3">
          <q-input v-model.number="model.lowerPrice" label="Нижняя граница" type="number" outlined dense :disable="disable" />
          <field-hint :text="hints.lowerPrice" />
        </div>
        <div class="col-6 col-md-3">
          <q-input v-model.number="model.upperPrice" label="Верхняя граница" type="number" outlined dense :disable="disable" />
          <field-hint :text="hints.upperPrice" />
        </div>
      </template>
      <template v-else>
        <div class="col-6 col-md-3">
          <q-select
            v-model="model.atrInterval" :options="atrIntervalOptions"
            option-value="value" option-label="label" emit-value map-options
            label="Интервал ATR" outlined dense :disable="disable"
          />
          <field-hint :text="hints.atrInterval" />
        </div>
        <div class="col-6 col-md-3">
          <q-input v-model.number="model.atrPeriods" label="Свечей ATR" type="number" min="5" outlined dense :disable="disable" />
          <field-hint :text="hints.atrPeriods" />
        </div>
        <div class="col-6 col-md-3">
          <q-input v-model.number="model.atrMultiplier" label="Множитель ATR" type="number" min="0.1" step="0.1" outlined dense :disable="disable" />
          <field-hint :text="hints.atrMultiplier" />
        </div>
        <div class="col-6 col-md-3">
          <q-input v-model.number="minHalfWidthPercent" label="Мин. полуширина" type="number" min="0.1" step="0.1" suffix="%" outlined dense :disable="disable" />
          <field-hint :text="hints.minHalfWidthPct" />
        </div>
        <div class="col-6 col-md-3">
          <q-input v-model.number="maxHalfWidthPercent" label="Макс. полуширина" type="number" min="0.1" step="0.1" suffix="%" outlined dense :disable="disable" />
          <field-hint :text="hints.maxHalfWidthPct" />
        </div>
        <div class="col-6 col-md-3">
          <q-select
            v-model="model.onUpperBreakout" :options="upperBreakoutOptions"
            option-value="value" option-label="label" emit-value map-options
            label="Пробой верхней границы" outlined dense :disable="disable"
          />
          <field-hint :text="hints.onUpperBreakout" />
        </div>
        <template v-if="model.onUpperBreakout === 'REPLACE_UPPER' || model.onRangeExit === 'REPLACE_LOWER'">
          <div class="col-6 col-md-3">
            <q-input v-model.number="model.breakoutConfirmSeconds" label="Подтверждение" type="number" min="1" suffix="сек" outlined dense :disable="disable" />
            <field-hint :text="hints.breakoutConfirmSeconds" />
          </div>
          <div class="col-6 col-md-3">
            <q-input v-model.number="breakoutMarginPercent" label="Запас пробоя" type="number" min="0" step="0.1" suffix="%" outlined dense :disable="disable" />
            <field-hint :text="hints.breakoutMarginPct" />
          </div>
          <div class="col-6 col-md-3">
            <q-input v-model.number="model.replaceCooldownSeconds" label="Пауза между заменами" type="number" min="1" suffix="сек" outlined dense :disable="disable" />
            <field-hint :text="hints.replaceCooldownSeconds" />
          </div>
        </template>
      </template>

      <div class="col-6 col-md-3">
        <q-input v-model.number="model.levels" label="Уровней" type="number" min="1" outlined dense :disable="disable" />
        <field-hint :text="hints.levels" />
      </div>
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.maxActiveOrders" label="Активных заявок" type="number" min="1" outlined dense :disable="disable" />
        <field-hint :text="hints.maxActiveOrders" />
      </div>
      <div class="col-6 col-md-3">
        <q-input
          v-model.number="model.minStepToCommissionRatio"
          label="Запас шага к комиссии"
          type="number" step="0.05" min="1.01"
          outlined dense :disable="disable"
        />
        <field-hint :text="hints.minStepToCommissionRatio" />
      </div>
      <div class="col-6 col-md-3">
        <q-select
          v-model="model.onRangeExit"
          :options="availableRangeExitOptions"
          option-value="value" option-label="label" emit-value map-options
          label="Выход из диапазона" outlined dense :disable="disable"
        />
        <field-hint :text="hints.onRangeExit" />
      </div>
      <template v-if="model.onRangeExit === 'REPLACE_LOWER'">
        <div class="col-6 col-md-3">
          <q-input
            v-model.number="model.maxDownwardReplacements"
            label="Перестановок вниз, макс."
            type="number" min="1" outlined dense :disable="disable"
          />
          <field-hint :text="hints.maxDownwardReplacements" />
        </div>
        <div class="col-6 col-md-3">
          <q-input
            v-model.number="model.maxRealizedLoss"
            label="Убыток перестановок, макс."
            type="number" min="0.01" step="0.01" outlined dense :disable="disable"
          />
          <field-hint :text="hints.maxRealizedLoss" />
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
        <field-hint :text="hints.sizingMode" />
      </div>

      <div v-if="model.sizingMode === 'FIXED_QUANTITY'" class="col-6 col-md-3">
        <!--
          Строкой, а не v-model.number: у крипты количество бывает с восемью знаками,
          и приведение к числу на каждое нажатие клавиши съедало бы хвост при вводе.
        -->
        <q-input
          v-model="model.quantityPerOrder"
          label="Количество в заявке" inputmode="decimal"
          :hint="preview.quantityStep ? `шаг ${formatQuantity(preview.quantityStep)}` : ''"
          outlined dense :disable="disable"
        />
        <field-hint :text="hints.quantityPerOrder" />
      </div>

      <template v-else>
        <div class="col-6 col-md-3">
          <q-input
            v-model.number="model.budget"
            label="Бюджет бота" type="number" min="0" step="0.01"
            outlined dense :disable="disable"
            :suffix="preview.cashCurrency || ''"
          />
          <field-hint :text="hints.budget" />
        </div>
        <div class="col-6 col-md-2">
          <q-input
            v-model.number="budgetPercent"
            label="% от свободных"
            type="number" min="0" max="100" step="1" suffix="%"
            outlined dense :disable="disable || preview.availableCash === null"
          />
          <field-hint :text="hints.budgetPercent" />
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
          <field-hint :text="hints.profitPolicy" />
        </div>
      </template>
    </div>

    <q-separator />

    <div class="text-subtitle2">Маржа</div>
    <div class="text-caption text-grey">
      Шорт — продажа того, чего нет. Убыток по нему сверху ничем не ограничен,
      поэтому потолки обязательны: пустое поле здесь означает запрет, а не свободу.
    </div>

    <q-toggle
      v-model="model.marginEnabled"
      label="Маржинальный режим"
      :disable="disable"
    />
    <field-hint :text="hints.marginEnabled" />

    <template v-if="model.marginEnabled">
      <q-banner dense class="bg-red-1 text-red-9">
        <template #avatar><q-icon name="warning" /></template>
        Маржу должно разрешать и подключение, и сам брокер по этой бумаге. Пока
        шортовая сетка допускается только в бумажном режиме — прогоните её без денег,
        прежде чем ставить на неё деньги.
      </q-banner>

      <q-toggle
        v-model="model.allowLiveMargin"
        label="Разрешить маржу на реальные деньги"
        :disable="disable"
      />
      <field-hint :text="hints.allowLiveMargin" />
      <q-banner v-if="model.allowLiveMargin && !model.dryRun" dense class="bg-red-2 text-red-10">
        <template #avatar><q-icon name="report" /></template>
        Шорт и переворот ни разу не работали на настоящем рынке. Начните с потолков,
        которые не жаль потерять целиком.
      </q-banner>

      <div class="row q-col-gutter-md">
        <div class="col-6 col-md-3">
          <q-select
            v-model="model.direction"
            :options="directionOptions"
            option-value="value" option-label="label"
            emit-value map-options
            label="Направление сетки"
            outlined dense :disable="disable"
          />
          <field-hint :text="hints.direction" />
        </div>
        <div class="col-6 col-md-3">
          <q-input
            v-model="model.maxShortQuantity"
            label="Шорт, макс. штук"
            inputmode="decimal" outlined dense :disable="disable"
          />
          <field-hint :text="hints.maxShortQuantity" />
        </div>
        <div class="col-6 col-md-3">
          <q-input
            v-model.number="model.maxShortNotional"
            label="Шорт, макс. денег"
            type="number" outlined dense :disable="disable"
          />
          <field-hint :text="hints.maxShortNotional" />
        </div>
        <div class="col-6 col-md-3">
          <q-input
            v-model.number="model.expectedCycleDays"
            label="Суток на цикл"
            type="number" outlined dense :disable="disable"
          />
          <field-hint :text="hints.expectedCycleDays" />
        </div>
      </div>

      <div class="text-subtitle2 q-mt-sm">Восстановительное плечо</div>
      <div class="text-caption text-grey">
        При пробое против позиции бот может не фиксировать убыток, а перевернуть позицию
        с множителем и отбивать его плечом. Каждый следующий переворот умножает экспозицию,
        поэтому лимит эпизодов — главный предохранитель.
      </div>

      <div class="row q-col-gutter-md">
        <div class="col-6 col-md-3">
          <q-select
            v-model="model.onAdverseBreakout"
            :options="adverseBreakoutOptions"
            option-value="value" option-label="label"
            emit-value map-options
            label="Пробой против позиции"
            outlined dense :disable="disable"
          />
          <field-hint :text="hints.onAdverseBreakout" />
        </div>
        <template v-if="model.onAdverseBreakout === 'HEDGE_AND_RECOVER'">
          <div class="col-6 col-md-3">
            <q-input v-model.number="model.hedgeMultiplier" label="Множитель" type="number"
                     step="0.5" min="1.5" outlined dense :disable="disable" />
            <field-hint :text="hints.hedgeMultiplier" />
          </div>
          <div class="col-6 col-md-3">
            <q-input v-model.number="model.maxHedgeEpisodes" label="Эпизодов на поколение"
                     type="number" min="0" outlined dense :disable="disable" />
            <field-hint :text="hints.maxHedgeEpisodes" />
          </div>
          <div class="col-6 col-md-3">
            <q-input v-model.number="model.maxHedgeHoldDays" label="Суток удержания, макс."
                     type="number" min="1" outlined dense :disable="disable" />
            <field-hint :text="hints.maxHedgeHoldDays" />
          </div>
          <div class="col-6 col-md-3">
            <q-input v-model.number="hedgeStopLossPercent" label="Стоп по плечу" type="number"
                     min="0" step="0.5" suffix="%" outlined dense :disable="disable" />
            <field-hint :text="hints.hedgeStopLossPct" />
          </div>
          <div class="col-6 col-md-3 flex items-center">
            <q-toggle v-model="model.hedgeAndGridConcurrent"
                      label="Сетка работает одновременно с плечом" :disable="disable" />
          </div>
        </template>
      </div>
    </template>

    <q-separator />

    <div class="text-subtitle2">Лимиты</div>
    <div class="text-caption text-grey">
      Проверяются перед каждой заявкой и считаются по журналу, поэтому переживают перезапуск.
      Пустое поле означает «без ограничения» — для реальных денег так лучше не оставлять.
    </div>

    <div class="row q-col-gutter-md">
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.maxCapital" label="Капитал, макс." type="number" outlined dense :disable="disable" />
        <field-hint :text="hints.maxCapital" />
      </div>
      <div class="col-6 col-md-3">
        <q-input v-model="model.maxPositionQuantity" label="Позиция, макс." inputmode="decimal" outlined dense :disable="disable" />
        <field-hint :text="hints.maxPositionQuantity" />
      </div>
      <div class="col-6 col-md-3">
        <q-input v-model.number="model.maxOrdersPerDay" label="Заявок в сутки" type="number" outlined dense :disable="disable" />
        <field-hint :text="hints.maxOrdersPerDay" />
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
        <field-hint :text="hints.maxOrdersPerMinute" />
      </div>
    </div>

    <div class="row items-center q-gutter-lg">
      <q-toggle v-model="model.dryRun" label="Бумажный режим (без реальных заявок)" :disable="disable">
        <field-hint :text="hints.dryRun" />
      </q-toggle>
      <q-toggle v-model="model.enabled" label="Стратегия включена" :disable="disable">
        <field-hint :text="hints.enabled" />
      </q-toggle>
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
          <th class="text-right">Количество</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in ladder" :key="row.level" :class="row.current ? 'bg-blue-1' : ''">
          <td>{{ row.level }}</td>
          <td class="text-right mono">{{ row.price }}</td>
          <td class="text-right mono">
            <span v-if="row.quantity !== null">{{ formatQuantity(row.quantity) }}</span>
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
import FieldHint from 'components/FieldHint.vue'

/**
 * Расшифровки полей: что параметр означает и как он двигает сетку.
 *
 * Держатся здесь, а не в подписях под полями, по двум причинам: подписи такой
 * длины превратили бы форму в стену текста, и они всё равно не поместились бы —
 * а знать, что «множитель ATR» задаёт заодно и шаг сетки, нужно ровно в момент,
 * когда его меняют.
 */
const hints = {
  instrumentUid:
    'Что торгует бот. От инструмента берутся шаг цены, шаг количества и минимальная '
    + 'сумма заявки — по ним округляются все уровни сетки и размер заявки. У работающего '
    + 'бота инструмент не меняют: к текущему привязаны его заявки и позиция.',

  autoRange:
    'Включено — границы считаются по волатильности (ATR) при первом запуске и '
    + 'сохраняются: рестарт не пересчитывает их заново, иначе уже открытые покупки '
    + 'потеряли бы цены своих встречных продаж. Выключено — границы задаются руками '
    + 'и не меняются никогда. Перестановка диапазона вверх и вниз работает только '
    + 'с автоматическими границами.',

  lowerPrice:
    'Цена самого нижнего уровня покупки. Ниже неё бот не покупает: при уходе цены '
    + 'за границу срабатывает действие из поля «Выход из диапазона».',

  upperPrice:
    'Цена самого верхнего уровня. На нём только продают — покупать там нечего, '
    + 'встречной продажи выше не существует. Вместе с нижней границей и числом уровней '
    + 'задаёт шаг сетки: шаг = (верх − низ) / уровней.',

  atrInterval:
    'Таймфрейм свечей, по которым считается ATR. Крупнее интервал — шире и спокойнее '
    + 'диапазон, реже перестановки. Мельче — сетка теснее и живее реагирует на рынок.',

  atrPeriods:
    'Сколько последних свечей входит в расчёт ATR. Больше — устойчивее оценка '
    + 'волатильности и стабильнее ширина сетки. Меньше — быстрее подстраивается под '
    + 'рынок, но дёргается на случайных всплесках.',

  atrMultiplier:
    'Во сколько ATR укладывается полуширина диапазона: границы = цена ± ATR × множитель. '
    + 'Прямо задаёт ширину сетки, а с ней и шаг: множитель вдвое больше — шаг вдвое '
    + 'крупнее, сделок реже, прибыль за цикл выше.',

  minHalfWidthPct:
    'Нижний предел полуширины в процентах от цены. Страховка от спокойного рынка: '
    + 'ATR может дать настолько узкий диапазон, что шаг перестанет окупать комиссию — '
    + 'такую сетку бот запускать откажется.',

  maxHalfWidthPct:
    'Верхний предел полуширины в процентах от цены. Страховка от паники: на всплеске '
    + 'ATR сетка растянулась бы так, что бюджет размазался бы по далёким уровням '
    + 'и сделок почти не стало.',

  onUpperBreakout:
    'Что делать, когда цена уверенно ушла ВЫШЕ верхней границы. «Ничего не делать» — '
    + 'ждать возврата цены. «Переставить вверх» — снять покупки, дождаться, пока позиция '
    + 'распродастся своими же встречными продажами (то есть с прибылью), и построить '
    + 'новую сетку вокруг текущей цены.',

  breakoutConfirmSeconds:
    'Сколько секунд цена должна продержаться за порогом, чтобы пробой считался '
    + 'настоящим. Защита от одиночного выброса: мало — бот будет перестраивать сетку '
    + 'на каждом шипе, много — опоздает за ушедшим рынком.',

  breakoutMarginPct:
    'Насколько дальше границы должна уйти цена, чтобы это считалось пробоем. Процент '
    + 'от границы; фактический запас — большее из этого процента и половины шага сетки. '
    + 'Нулевой запас означал бы пробой при любом касании границы.',

  replaceCooldownSeconds:
    'Минимальная пауза между перестановками диапазона. Не даёт перестраивать сетку '
    + 'раз за разом на пиле: пока пауза не истекла, подтверждённый пробой не приводит '
    + 'к замене.',

  levels:
    'Число интервалов сетки — уровней цен будет на один больше. Шаг = (верх − низ) / '
    + 'уровней, округлённый к шагу цены инструмента. Больше уровней — мельче шаг, чаще '
    + 'сделки, но меньше прибыль за цикл; слишком мелкий шаг перестаёт окупать комиссию, '
    + 'и бот откажется стартовать. Покупка на уровне i закрывается продажей на i+1.',

  maxActiveOrders:
    'Сколько заявок бот держит на бирже одновременно — покупки и продажи вместе. Если '
    + 'меньше числа уровней, дальние от рынка уровни останутся без заявок: ближние к цене '
    + 'выставляются первыми. Снижает нагрузку на биржу, но урезает работающую часть сетки.',

  minStepToCommissionRatio:
    'Во сколько раз шаг сетки обязан перекрывать комиссию за оборот — этим и решается, '
    + 'насколько мелкую сетку бот согласится построить. При 1.5 цикл зарабатывает '
    + 'половину комиссии сверх неё; при 1.1 — десятую часть, и любое проскальзывание '
    + 'съедает её целиком. Единица означает работу в ноль, меньше единицы — убыток '
    + 'на каждом закрытом цикле: бот такую сетку построит, потому что решение ваше, '
    + 'но оборот в этом случае набирается за ваш счёт.',

  onRangeExit:
    'Что делать, когда цена ушла НИЖЕ нижней границы — главный риск конструкции. '
    + '«Перестать покупать» — позиция замирает, продажи висят до возврата цены. '
    + '«Снять заявки и остановиться» — жёстче, но предсказуемее. «Закрыть позицию '
    + 'и переставить вниз» — продать позицию по рынку, зафиксировать убыток и построить '
    + 'сетку заново ниже; только с автоматическими границами и только в пределах '
    + 'бюджета убытка.',

  maxDownwardReplacements:
    'Сколько раз бот вправе переставить сетку вниз. Счётчик накопительный и переживает '
    + 'рестарт: когда лимит исчерпан, при следующем пробое вниз бот выключится, оставив '
    + 'позицию на руках. Обнуляется ручной перестройкой сетки с фиксацией убытка.',

  maxRealizedLoss:
    'Потолок суммарного убытка от перестановок вниз, в валюте инструмента. Считается '
    + 'накопительно по всем перестановкам, а прогноз проверяется ДО продажи: если '
    + 'закрытие позиции в потолок не укладывается, бот не тронет позицию и выключится. '
    + 'Это лимит риска, а не результат бота: на P/L он не влияет и обнуляется ручной '
    + 'перестройкой сетки.',

  sizingMode:
    'Как считается размер заявки. «Фиксированное количество» — одно и то же число на '
    + 'каждом уровне, бюджет не участвует. «Один размер на все уровни» — размер '
    + 'подбирается так, чтобы выкуп всех уровней уложился в бюджет. «Поровну денег на '
    + 'уровень» — на каждый уровень идёт равная сумма, поэтому внизу сетки количество '
    + 'больше, чем вверху.',

  quantityPerOrder:
    'Количество в одной заявке — в единицах базового актива (штуки, монеты), не в лотах. '
    + 'Округляется вниз к шагу количества биржи. Заявка мельче биржевого минимума будет '
    + 'отклонена риск-контролем ещё до отправки.',

  budget:
    'Деньги, из которых считается размер заявки. Конкретная сумма, а не доля портфеля: '
    + 'доля молча передвигала бы деньги бота при каждой перестройке сетки. Размер заявки '
    + 'подбирается так, чтобы полный выкуп ВСЕХ уровней уложился в бюджет.',

  budgetPercent:
    'Вспомогательное поле формы: считает процент от свободных денег счёта, а кнопка '
    + '«Подставить» превращает его в конкретную сумму. Сам процент никуда не '
    + 'сохраняется и на бота не влияет.',

  profitPolicy:
    'Что делать с заработанным. «Выводится» — рабочий бюджет всегда равен заданному, '
    + 'прибыль копится отдельно. «Реинвестируется» — рабочий бюджет = бюджет + '
    + 'реализованный P/L, заявки постепенно растут. Пересчёт идёт только в моменты '
    + 'перестройки сетки, поэтому объём не «плывёт» между покупкой и её встречной продажей.',

  marginEnabled:
    'Разрешает боту короткую позицию. Работает только вместе с галкой на подключении: '
    + 'подключение разрешает, бот включает. Снятие галки на подключении гасит маржу '
    + 'у всех его ботов разом.',

  direction:
    'Лонг покупает ниже и продаёт выше, шорт — зеркально: продаёт выше и откупает ниже. '
    + 'Направление принадлежит поколению и внутри него не меняется — иначе уже '
    + 'выставленные встречные заявки закрывали бы не то, что открывали.',

  maxShortQuantity:
    'Потолок короткой позиции в единицах актива. Отдельный от «Позиция, макс.» намеренно: '
    + 'у длинной позиции убыток ограничен снизу нулём цены, у короткой сверху не ограничен '
    + 'ничем. Пусто — шорт запрещён.',

  maxShortNotional:
    'Потолок короткой позиции в деньгах. Штуки между инструментами несравнимы, а кончаются '
    + 'именно деньги. Пусто — шорт запрещён: здесь «забыл задать» не должно означать '
    + '«разрешено сколько угодно».',

  expectedCycleDays:
    'Сколько суток, по ожиданию, живёт один цикл шортовой сетки. Участвует в проверке, '
    + 'окупает ли шаг плату за перенос непокрытой позиции. Прогнозом не является: '
    + 'сетка с двухсуточным циклом обязана окупать двое суток удержания.',

  allowLiveMargin:
    'Разрешает маржинальные операции на реальные деньги, а не только на бумаге. '
    + 'Отдельно от «Маржинальный режим» намеренно: шорт и переворот ни разу не работали '
    + 'на настоящем рынке — проверена арифметика, но не поведение брокера.',

  onAdverseBreakout:
    'Что делать при пробое против позиции. «Закрыть» — прежнее поведение: фиксируем '
    + 'убыток и переставляем сетку. «Перевернуть» — продаём (откупаем) с множителем: '
    + 'часть закрывает позицию, остаток становится плечом, которое отбивает убыток.',

  hedgeMultiplier:
    'Во сколько раз больше закрываемой позиции продаём. При ×4 для возврата в ноль цене '
    + 'надо пройти втрое меньше, чем она уже прошла. Множитель определяет цену безубытка, '
    + 'поэтому если плечо не влезает в потолки, бот отказывается от переворота целиком, '
    + 'а не уменьшает множитель молча.',

  maxHedgeEpisodes:
    'Сколько переворотов разрешено на одно поколение. Главный предохранитель: каждый '
    + 'следующий умножает экспозицию — два подряд дают девятикратную от исходной, '
    + 'три двадцатисемикратную. Ноль запрещает переворот вовсе.',

  maxHedgeHoldDays:
    'Сколько суток держим плечо, прежде чем закрыть по рынку и признать результат. '
    + 'Срок — крайний рубеж, а не план: чем он длиннее, тем больше плата за перенос.',

  hedgeStopLossPct:
    'Насколько цена вправе уйти против плеча, прежде чем эпизод закроется с убытком. '
    + 'Пусто — стоп не задан, и эпизод держится до цели или до срока.',

  maxCapital:
    'Потолок денег, одновременно занятых заявками и позицией. Проверяется перед каждой '
    + 'заявкой и считается по журналу, поэтому переживает перезапуск. Пусто — без '
    + 'ограничения.',

  maxPositionQuantity:
    'Потолок позиции в единицах базового актива. Заявка, после которой позиция вышла бы '
    + 'за него, не будет выставлена. Пусто — без ограничения.',

  maxOrdersPerDay:
    'Сколько заявок бот вправе выставить за сутки. Считается по журналу. Защита от '
    + 'сценария, где ошибка в логике за час выбирает суточный лимит биржи.',

  maxOrdersPerMinute:
    'Частотный ограничитель: на стриме нет естественного тормоза, каким был период '
    + 'опроса. Слишком мало — сетка будет медленно восстанавливаться после открытия '
    + 'торгов и растянет перестановку; слишком много — защита от разгона перестаёт '
    + 'работать.',

  dryRun:
    'Бумажный режим: заявки идут в тот же журнал, но не на биржу, а исполнение '
    + 'симулируется оптимистично — без очереди заявок и проскальзывания. Проверяет '
    + 'логику стратегии, но не доходность.',

  enabled:
    'Выключенная стратегия запускается, но не торгует: бот поднимется, напишет об этом '
    + 'в журнал и не выставит ни одной заявки. Удобно, чтобы временно погасить бота, '
    + 'не теряя настроек.'
}

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
  { value: 'FIXED_QUANTITY', label: 'Фиксированное количество' },
  { value: 'UNIFORM', label: 'Один размер на все уровни (от бюджета)' },
  { value: 'PER_LEVEL', label: 'Поровну денег на уровень (от бюджета)' }
]

const profitPolicyOptions = [
  { value: 'WITHDRAW', label: 'Выводится (бюджет фиксирован)' },
  { value: 'COMPOUND', label: 'Реинвестируется' }
]

const adverseBreakoutOptions = [
  { value: 'LIQUIDATE', label: 'Закрыть позицию и зафиксировать убыток' },
  { value: 'HEDGE_AND_RECOVER', label: 'Перевернуть с множителем и отбивать плечом' }
]

const directionOptions = [
  { value: 'LONG', label: 'Лонг — покупаем ниже, продаём выше' },
  { value: 'SHORT', label: 'Шорт — продаём выше, откупаем ниже' }
]

const defaults = {
  instrumentUid: '',
  autoRange: false,
  lowerPrice: null,
  upperPrice: null,
  levels: 10,
  quantityPerOrder: 1,
  // FIXED_QUANTITY по умолчанию: создание бота «по-старому» не меняется,
  // на бюджет переходят осознанно.
  sizingMode: 'FIXED_QUANTITY',
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
  maxPositionQuantity: null,
  maxOrdersPerDay: null,
  maxOrdersPerMinute: 10,
  // Маржа выключена по умолчанию: право рисковать даётся явно.
  marginEnabled: false,
  direction: 'LONG',
  maxShortQuantity: null,
  maxShortNotional: null,
  expectedCycleDays: 1,
  allowLiveMargin: false,
  onAdverseBreakout: 'LIQUIDATE',
  hedgeMultiplier: 4,
  maxHedgeEpisodes: 1,
  maxHedgeHoldDays: 3,
  hedgeStopLossPct: null,
  hedgeAndGridConcurrent: true,
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
  if (!p.ready || !p.sizingMode || p.sizingMode === 'FIXED_QUANTITY') return ''

  const quantities = (p.quantityByLevel || []).map(Number)
  if (!quantities.length) return ''
  const min = Math.min(...quantities)
  const max = Math.max(...quantities)
  const size = min === max
    ? `по ${formatQuantity(min)} на уровень`
    : `от ${formatQuantity(min)} до ${formatQuantity(max)} по уровням`
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

/**
 * Количество уходит на бэкенд СТРОКОЙ, как пользователь его ввёл.
 *
 * Намеренно: числа JavaScript — это double, и 0.12345678 через них уже не проходит
 * без потерь. Строку Jackson читает в BigDecimal ровно теми цифрами, что набраны,
 * а деньги и количество монет — не то место, где уместна потеря последнего знака.
 */
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
  if (mode === 'FIXED_QUANTITY') {
    if (model.quantityPerOrder == null) model.quantityPerOrder = 1
  } else {
    model.quantityPerOrder = null
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

// Процент живёт только в форме: на backend уходит доля.
const hedgeStopLossPercent = computed({
  get: () => model.hedgeStopLossPct == null ? null : Number(model.hedgeStopLossPct) * 100,
  set: v => { model.hedgeStopLossPct = (v == null || v === '') ? null : Number(v) / 100 }
})

watch(() => model.marginEnabled, enabled => {
  if (!enabled) {
    // Шорт без маржи не собирается на бэкенде вовсе — возвращаем лонг сами,
    // чтобы человек не получил отказ при сохранении вместо понятной формы.
    model.direction = 'LONG'
    model.allowLiveMargin = false
    model.onAdverseBreakout = 'LIQUIDATE'
    model.maxShortQuantity = null
    model.maxShortNotional = null
  }
  // Бумажный режим НЕ трогаем ни при каких обстоятельствах.
  //
  // Здесь стояло автоматическое включение dryRun при включении маржи — «подсказать
  // формой, а не отказом». Это было грубой ошибкой: у бота с живыми заявками на
  // бирже переключение в бумажный режим означает, что гейтвей перестаёт видеть
  // и вести настоящие ордера, а исполнения начинает выдумывать из потока цен.
  // Заявки при этом остаются на бирже без присмотра, а книга раздваивается —
  // бумажные записи ведутся отдельно от боевых.
  //
  // Режим торговли — решение человека и только его. Ни одна настройка не вправе
  // переключать его молча: тем и опасно «удобное» автоповедение, что оно меняет
  // самое важное, пока внимание занято другим.
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

const preview = ref({ ready: false, error: '', ladderPrices: [], quantityByLevel: [], quantityStep: null, availableCash: null, cashCurrency: null })
const previewLoading = ref(false)
let previewTimer = null
let previewVersion = 0

function canPreview () {
  if (!props.connectionId || !String(model.instrumentUid || '').trim()) return false
  if (!model.autoRange && (model.lowerPrice == null || model.upperPrice == null)) return false
  return true
}

function clearPreview () {
  preview.value = { ready: false, error: '', ladderPrices: [], quantityByLevel: [], quantityStep: null, availableCash: null, cashCurrency: null }
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
      quantityByLevel: [],
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
      quantity: (preview.value.quantityByLevel || [])[level] ?? null,
      current: false
    }))
    .reverse()
})

/**
 * Количество: значащие знаки не теряем.
 *
 * Обычное форматирование с двумя знаками превратило бы 0.000001 BTC в «0», то есть
 * соврало бы о размере заявки. Поэтому до восьми знаков и без группировки.
 */
function formatQuantity (value) {
  if (value === null || value === undefined) return '—'
  const number = Number(value)
  if (Number.isNaN(number)) return String(value)
  return number.toLocaleString('ru-RU', { maximumFractionDigits: 8, useGrouping: false })
}

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
