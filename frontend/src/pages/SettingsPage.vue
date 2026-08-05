<template>
  <q-page class="q-pa-md">
    <div class="row items-center justify-between q-mb-md">
      <div class="text-h5">Настройки</div>
      <q-btn dense flat round icon="refresh" :loading="loading" @click="load" />
    </div>

    <div class="settings-column q-gutter-md">
      <!-- Стоп-кран -->
      <q-card flat bordered>
        <q-card-section class="row items-center justify-between">
          <div>
            <div class="text-subtitle1">Торговля</div>
            <div class="text-caption text-grey">
              Аварийный стоп-кран. При выключении ни один бот не сможет выставить ордер —
              немедленно, не дожидаясь их остановки.
            </div>
          </div>

          <q-toggle
            v-model="tradingEnabled"
            :disable="savingTrading || loading"
            color="positive"
            keep-color
            size="lg"
            @update:model-value="onTradingToggle"
          />
        </q-card-section>

        <q-separator />

        <q-card-section>
          <q-banner v-if="!tradingEnabled" dense class="bg-red-1 text-red-10" rounded>
            <template #avatar>
              <q-icon name="block" color="negative" />
            </template>
            Торговля остановлена. Боты могут числиться работающими, но ордера выставляться не будут.
          </q-banner>
          <div v-else class="text-caption text-positive">
            <q-icon name="check_circle" size="xs" class="q-mr-xs" />
            Торговля разрешена.
          </div>
        </q-card-section>
      </q-card>

      <!-- Telegram -->
      <q-card flat bordered>
        <q-card-section>
          <div class="text-subtitle1">Telegram</div>
          <div class="text-caption text-grey">
            Канал уведомлений о событиях ботов. Хранится в базе, а не в файле конфигурации.
          </div>
        </q-card-section>

        <q-separator />

        <q-card-section class="q-gutter-md">
          <div class="row items-center q-gutter-sm">
            <q-badge
              :color="data.telegramActive ? 'positive' : (data.hasTelegramToken ? 'warning' : 'grey-6')"
              :label="telegramStatusLabel"
              outline
            />
            <span v-if="data.hasTelegramToken" class="text-caption text-grey mono">
              {{ data.telegramTokenMasked }}
            </span>
          </div>

          <q-banner
            v-if="data.hasTelegramToken && !data.telegramActive"
            dense
            class="bg-orange-1 text-orange-10"
            rounded
          >
            <template #avatar>
              <q-icon name="restart_alt" color="orange-9" />
            </template>
            Токен сохранён, но бот в этом процессе не зарегистрирован.
            Перезапустите приложение, чтобы уведомления заработали.
          </q-banner>

          <q-input
            v-model="form.token"
            label="Токен бота"
            outlined
            dense
            :type="showToken ? 'text' : 'password'"
            :disable="savingTelegram"
            :hint="data.hasTelegramToken
              ? 'Оставьте пустым, чтобы не менять сохранённый токен'
              : 'Получите у @BotFather, вид <id>:<секрет>'"
          >
            <template #append>
              <q-icon
                :name="showToken ? 'visibility_off' : 'visibility'"
                class="cursor-pointer"
                @click="showToken = !showToken"
              />
            </template>
          </q-input>

          <q-input
            v-model="form.username"
            label="Имя бота (@username)"
            outlined
            dense
            :disable="savingTelegram"
          />

          <div class="row justify-between items-center">
            <q-btn
              flat
              dense
              color="negative"
              label="Удалить токен"
              :disable="!data.hasTelegramToken || savingTelegram"
              @click="confirmClear = true"
            />
            <q-btn
              color="primary"
              label="Сохранить"
              :loading="savingTelegram"
              :disable="savingTelegram"
              @click="saveTelegram"
            />
          </div>

          <div class="text-caption text-grey">
            После сохранения откройте чат с ботом и отправьте <span class="mono">/start</span>,
            чтобы подписаться на уведомления.
          </div>
        </q-card-section>
      </q-card>

      <!-- Шифрование -->
      <q-card flat bordered>
        <q-card-section>
          <div class="text-subtitle1">Хранение секретов</div>
        </q-card-section>

        <q-separator />

        <q-card-section>
          <div v-if="data.secretsEncrypted" class="text-positive">
            <q-icon name="lock" size="xs" class="q-mr-xs" />
            Ключи брокера и токен Telegram шифруются в базе.
          </div>
          <div v-else>
            <q-banner dense class="bg-orange-1 text-orange-10" rounded>
              <template #avatar>
                <q-icon name="lock_open" color="orange-9" />
              </template>
              Секреты хранятся в базе <b>открытым текстом</b>.
              Задайте переменную окружения <span class="mono">APP_SECRET_KEY</span> и перезапустите
              приложение, затем пересохраните ключи, чтобы они зашифровались.
            </q-banner>
          </div>
        </q-card-section>
      </q-card>
    </div>

    <q-dialog v-model="confirmClear">
      <q-card style="min-width: 420px">
        <q-card-section class="row items-center q-gutter-sm">
          <q-icon name="warning" color="negative" size="md" />
          <div class="text-subtitle1">Удалить токен Telegram?</div>
        </q-card-section>
        <q-card-section class="text-grey">
          Уведомления перестанут приходить после перезапуска приложения.
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat label="Отмена" v-close-popup />
          <q-btn color="negative" label="Удалить" :loading="savingTelegram" @click="clearToken" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, reactive, computed, onMounted, inject } from 'vue'
import { apiClient, getErrorMessage } from 'src/services/apiClient'

const toast = inject('toast')

const loading = ref(false)
const savingTelegram = ref(false)
const savingTrading = ref(false)
const showToken = ref(false)
const confirmClear = ref(false)

const data = ref({
  hasTelegramToken: false,
  telegramTokenMasked: '',
  telegramBotUsername: '',
  telegramActive: false,
  tradingEnabled: true,
  secretsEncrypted: false
})

const tradingEnabled = ref(true)
const form = reactive({ token: '', username: '' })

const telegramStatusLabel = computed(() => {
  if (data.value.telegramActive) return 'Подключён'
  if (data.value.hasTelegramToken) return 'Нужен перезапуск'
  return 'Не настроен'
})

onMounted(load)

async function load () {
  loading.value = true
  try {
    data.value = await apiClient.get('/api/v1/settings')
    tradingEnabled.value = data.value.tradingEnabled
    form.username = data.value.telegramBotUsername || ''
    form.token = ''
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось загрузить настройки'))
  } finally {
    loading.value = false
  }
}

async function onTradingToggle (value) {
  savingTrading.value = true
  try {
    await apiClient.patch('/api/v1/settings/trading', { enabled: value })
    toast?.[value ? 'ok' : 'warn'](value ? 'Торговля разрешена' : 'Торговля остановлена')
    await load()
  } catch (e) {
    tradingEnabled.value = !value
    toast?.err(getErrorMessage(e, 'Не удалось переключить'))
  } finally {
    savingTrading.value = false
  }
}

async function saveTelegram () {
  savingTelegram.value = true
  try {
    await apiClient.put('/api/v1/settings/telegram', {
      token: form.token,
      username: form.username,
      clearToken: false
    })
    toast?.ok('Сохранено')
    await load()
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось сохранить'))
  } finally {
    savingTelegram.value = false
  }
}

async function clearToken () {
  savingTelegram.value = true
  try {
    await apiClient.put('/api/v1/settings/telegram', {
      token: '',
      username: form.username,
      clearToken: true
    })
    confirmClear.value = false
    toast?.ok('Токен удалён')
    await load()
  } catch (e) {
    toast?.err(getErrorMessage(e, 'Не удалось удалить'))
  } finally {
    savingTelegram.value = false
  }
}
</script>

<style scoped>
.settings-column {
  max-width: 760px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
}
</style>
