<template>
  <q-page class="q-pa-md">
    <div class="row items-center justify-between q-mb-md">
      <div class="text-h5">Обзор</div>
      <q-btn dense flat round icon="refresh" :loading="loading" @click="loadAll" />
    </div>

    <div class="row q-col-gutter-md">
      <!-- Подключения -->
      <div class="col-12 col-md-6">
        <q-card flat bordered>
          <q-card-section class="row items-center justify-between">
            <div class="text-subtitle1">Подключения</div>
            <q-btn dense flat label="Открыть" color="primary" to="/exchanges" />
          </q-card-section>

          <q-separator />

          <q-list separator>
            <q-item v-for="c in connections" :key="c.id">
              <q-item-section>
                <q-item-label>{{ c.name || 'Без названия' }}</q-item-label>
                <q-item-label caption>{{ c.exchange }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <div class="row items-center q-gutter-sm no-wrap">
                  <connection-valuation-cell :valuation="c.valuation" dense />
                  <q-badge
                    :color="stateColor(c.runtimeState)"
                    :label="stateLabel(c.runtimeState)"
                    outline
                  />
                </div>
              </q-item-section>
            </q-item>

            <q-item v-if="!loading && connections.length === 0">
              <q-item-section class="text-grey">Подключений пока нет</q-item-section>
            </q-item>
          </q-list>
        </q-card>
      </div>

      <!-- Боты -->
      <div class="col-12 col-md-6">
        <q-card flat bordered>
          <q-card-section class="row items-center justify-between">
            <div class="text-subtitle1">Боты</div>
            <q-btn dense flat label="Открыть" color="primary" to="/bots" />
          </q-card-section>

          <q-separator />

          <q-list separator>
            <q-item v-for="b in bots" :key="b.id">
              <q-item-section>
                <q-item-label>{{ b.name }}</q-item-label>
                <q-item-label caption>
                  {{ b.strategyType }} · {{ b.exchangeConnectionName || '—' }}
                </q-item-label>
              </q-item-section>
              <q-item-section side>
                <div class="row items-center q-gutter-sm no-wrap">
                  <bot-valuation-cell :valuation="b.valuation" dense />
                  <!-- Бот, который должен работать, но не работает: супервизор его поднимает -->
                  <q-icon
                    v-if="needsAttention(b)"
                    name="sync_problem"
                    color="warning"
                    size="xs"
                  >
                    <q-tooltip>Должен работать, но не запущен — идут повторные попытки</q-tooltip>
                  </q-icon>
                  <q-badge
                    :color="stateColor(b.runtime && b.runtime.state)"
                    :label="stateLabel(b.runtime && b.runtime.state)"
                    outline
                  />
                </div>
              </q-item-section>
            </q-item>

            <q-item v-if="!loading && bots.length === 0">
              <q-item-section class="text-grey">Ботов пока нет</q-item-section>
            </q-item>
          </q-list>
        </q-card>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { apiClient } from 'src/services/apiClient'
import { stateColor, stateLabel, isRuntimeActive } from 'src/services/runtimeState'
import BotValuationCell from 'components/BotValuationCell.vue'
import ConnectionValuationCell from 'components/ConnectionValuationCell.vue'

const connections = ref([])
const bots = ref([])
const loading = ref(false)

let timer = null

function needsAttention (bot) {
  return bot.active && !isRuntimeActive(bot.runtime && bot.runtime.state)
}

async function loadAll () {
  loading.value = true
  try {
    const [conn, bot] = await Promise.all([
      apiClient.get('/api/v1/exchange-connections', { params: { page: 0, size: 100 } }),
      apiClient.get('/api/v1/bots')
    ])
    connections.value = Array.isArray(conn?.content) ? conn.content : []
    bots.value = Array.isArray(bot) ? bot : []
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadAll()
  timer = setInterval(loadAll, 5000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>
