<template>
  <q-select
    v-model="selected"
    :options="options"
    :loading="loading"
    :disable="disable || !exchange"
    :label="label"
    :hint="hintText"
    option-value="uid"
    option-label="ticker"
    outlined
    dense
    clearable
    use-input
    hide-selected
    fill-input
    input-debounce="300"
    @filter="onFilter"
    @update:model-value="onSelect"
  >
    <template #option="scope">
      <q-item v-bind="scope.itemProps">
        <q-item-section>
          <q-item-label>
            <span class="text-weight-medium">{{ scope.opt.ticker }}</span>
            <span class="q-ml-sm">{{ scope.opt.name }}</span>
          </q-item-label>
          <q-item-label caption>{{ subtitle(scope.opt) }}</q-item-label>
        </q-item-section>
        <q-item-section side>
          <q-item-label caption>лот {{ scope.opt.lot }}</q-item-label>
          <!-- Отметка справочная: бот всё равно переспросит право на шорт у биржи. -->
          <q-item-label v-if="scope.opt.shortEnabled === false" caption class="text-negative">
            без шорта
          </q-item-label>
        </q-item-section>
      </q-item>
    </template>

    <template #no-option>
      <q-item>
        <q-item-section class="text-grey">
          <template v-if="loading">Поиск…</template>
          <template v-else-if="lastQuery.length && lastQuery.length < MIN_QUERY">
            Введите минимум {{ MIN_QUERY }} символа
          </template>
          <template v-else>
            Ничего не найдено. Если справочник ещё не загружен — запустите подключение
            к бирже и обновите справочник на странице «Биржи».
          </template>
        </q-item-section>
      </q-item>
    </template>
  </q-select>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { apiClient, getErrorMessage } from 'src/services/apiClient'
import { instrumentKindLabel } from 'src/services/runtimeState'

const MIN_QUERY = 2

const props = defineProps({
  /** Наружу ходит только uid — именно он лежит в конфиге бота. */
  modelValue: { type: String, default: null },
  /** Биржа выбранного подключения; без неё искать негде. */
  exchange: { type: String, default: null },
  kind: { type: String, default: null },
  disable: { type: Boolean, default: false },
  label: { type: String, default: 'Инструмент' },
  hint: { type: String, default: 'Начните вводить тикер или название' }
})

const emit = defineEmits(['update:modelValue', 'instrument'])

// Полный объект держим здесь, а emit-value/map-options не используем сознательно:
// map-options не умеет разрешить uid, которого нет в текущем списке options, —
// а это ровно ситуация открытия уже сохранённого бота.
const selected = ref(null)
const options = ref([])
const loading = ref(false)
const lastQuery = ref('')

// Ответы приходят вразнобой; без счётчика медленный ответ мог бы перетереть более свежий.
let seq = 0

const hintText = computed(() => (props.exchange ? props.hint : 'Сначала выберите подключение'))

function subtitle (opt) {
  return [
    instrumentKindLabel(opt.kind),
    opt.venue,
    opt.classCode,
    opt.currency
  ].filter(Boolean).join(' · ')
}

async function onFilter (val, update, abort) {
  if (!props.exchange) {
    abort()
    return
  }

  const q = (val || '').trim()
  lastQuery.value = q
  if (q.length && q.length < MIN_QUERY) {
    update(() => { options.value = [] })
    return
  }

  const my = ++seq
  loading.value = true
  try {
    const data = await apiClient.get('/api/v1/instruments', {
      params: {
        exchange: props.exchange,
        query: q || undefined,
        kind: props.kind || undefined,
        limit: 20
      }
    })
    if (my !== seq) return
    update(() => { options.value = Array.isArray(data) ? data : [] })
  } catch (e) {
    console.debug(getErrorMessage(e))
    if (my === seq) update(() => { options.value = [] })
    abort()
  } finally {
    if (my === seq) loading.value = false
  }
}

function onSelect (opt) {
  emit('update:modelValue', opt?.uid ?? null)
  emit('instrument', opt ?? null)
}

/** Открыли существующего бота: в конфиге лежит только uid — тянем подпись с бэка. */
async function resolve (uid) {
  if (!uid) {
    selected.value = null
    return
  }
  if (selected.value?.uid === uid) return

  try {
    const dto = await apiClient.get(`/api/v1/instruments/${encodeURIComponent(uid)}`, {
      params: { exchange: props.exchange || undefined }
    })
    selected.value = dto
    emit('instrument', dto)
  } catch (e) {
    console.debug(getErrorMessage(e))
    // Справочник пуст или инструмент делистнут. Показываем хотя бы uid: иначе поле
    // выглядит пустым, и пользователь решит, что настройка потерялась.
    // shortEnabled намеренно null, а не false: «не знаем» и «брокер запретил» —
    // разные вещи, и вторую нельзя показывать, не спросив.
    selected.value = {
      uid, ticker: uid, name: '(нет в справочнике)',
      kind: '', venue: '', classCode: '', currency: '', lot: 1,
      shortEnabled: null, shortInitialMarginRate: null
    }
    emit('instrument', selected.value)
  }
}

watch(() => props.modelValue, resolve, { immediate: true })

watch(() => props.exchange, () => {
  options.value = []
  selected.value = null
  resolve(props.modelValue)
})
</script>
