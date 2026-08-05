<template>
  <div class="column items-end justify-center bot-valuation" :class="{ 'text-caption': dense }">
    <div class="bot-valuation__budget">
      {{ v.budget }}
      <q-tooltip anchor="top middle" self="bottom middle">{{ v.budgetHint }}</q-tooltip>
    </div>
    <div class="bot-valuation__balance" :class="{ 'text-grey-6': v.stale }">
      {{ v.balance }}
      <q-tooltip anchor="top middle" self="bottom middle">{{ v.balanceHint }}</q-tooltip>
    </div>
    <div class="bot-valuation__pnl" :class="[v.tone, { 'text-grey-6': v.stale }]">
      <q-icon v-if="v.partial" name="help_outline" size="12px" class="q-mr-xs" />
      <q-icon v-else-if="v.stale" name="schedule" size="12px" class="q-mr-xs" />
      {{ v.pnl }}
      <q-tooltip anchor="top middle" self="bottom middle">
        {{ v.pnlHint }}
        <template v-if="valuation && valuation.lastPriceAt">
          <br>Цена от {{ formatTime(valuation.lastPriceAt) }}
        </template>
        <template v-if="valuation && valuation.profitPolicy === 'WITHDRAW'">
          <br>Выведено прибыли: {{ formatMoney(valuation.withdrawnProfit) }}
        </template>
      </q-tooltip>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { formatValuation, formatMoney, formatTime } from 'src/services/money'

const props = defineProps({
  valuation: { type: Object, default: null },
  dense: { type: Boolean, default: false }
})

const v = computed(() => formatValuation(props.valuation))
</script>

<style scoped>
.bot-valuation {
  line-height: 1.2;
  white-space: nowrap;
}

/* Бюджет — опорная величина, но не главная: он приглушён, чтобы баланс читался первым. */
.bot-valuation__budget {
  font-variant-numeric: tabular-nums;
  font-size: 0.8em;
  color: #9e9e9e;
}

.bot-valuation__balance {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.bot-valuation__pnl {
  font-variant-numeric: tabular-nums;
  font-size: 0.85em;
}
</style>
