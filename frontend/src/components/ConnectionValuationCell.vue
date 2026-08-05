<template>
  <div class="column items-end justify-center connection-valuation" :class="{ 'text-caption': dense }">
    <div class="connection-valuation__total">
      <q-icon v-if="v.incomplete" name="help_outline" size="12px" class="q-mr-xs text-grey-6" />
      {{ v.total }}
      <q-tooltip anchor="top middle" self="bottom middle">{{ v.hint }}</q-tooltip>
    </div>
    <div v-if="v.pnl" class="connection-valuation__pnl" :class="v.tone">
      {{ v.pnl }}
      <q-tooltip anchor="top middle" self="bottom middle">
        Суммарный P/L всех ботов подключения
      </q-tooltip>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { formatConnectionValuation } from 'src/services/money'

const props = defineProps({
  valuation: { type: Object, default: null },
  dense: { type: Boolean, default: false }
})

const v = computed(() => formatConnectionValuation(props.valuation))
</script>

<style scoped>
.connection-valuation {
  line-height: 1.2;
  white-space: nowrap;
}

.connection-valuation__total {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.connection-valuation__pnl {
  font-variant-numeric: tabular-nums;
  font-size: 0.85em;
}
</style>
