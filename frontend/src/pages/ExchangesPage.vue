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
                  {{ item.exchange }} · {{ item.active ? 'Активно' : 'Выключено' }}
                </q-item-label>
              </q-item-section>

              <q-item-section side>
                <q-badge
                  :label="item.active ? 'ON' : 'OFF'"
                  :color="item.active ? 'positive' : 'grey-6'"
                  outline
                />
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
              dense
              flat
              icon="delete"
              color="negative"
              :disable="!detail || detailLoading"
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
                <div class="text-h6">{{ detail.name || 'Без названия' }}</div>
                <div class="text-caption text-grey">
                  {{ detail.exchange }} · {{ detail.active ? 'Активно' : 'Выключено' }}
                </div>
                <div class="text-caption text-grey q-mt-xs">
                  ID: <span class="mono">{{ detail.id }}</span>
                </div>
              </q-card-section>

              <q-separator />

              <q-card-section class="q-gutter-md">
                <div class="row q-col-gutter-md">
                  <div class="col-12 col-md-6">
                    <div class="text-caption text-grey">API Key</div>
                    <div class="mono">{{ detail.apiKeyMasked || '—' }}</div>
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
import { ref, computed, onMounted, reactive } from 'vue'
import { api } from 'boot/axios'
import { Notify } from 'quasar'

const list = ref([])
const listLoading = ref(false)

const selectedId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)

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

onMounted(async () => {
  await Promise.all([
    loadTypes(),
    loadList()
  ])
})

async function loadList () {
  listLoading.value = true
  try {
    const { data } = await api.get('/api/v1/exchange-connections', {
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
  await loadDetail(id)
}

async function loadDetail (id) {
  detailLoading.value = true
  detail.value = null
  try {
    const { data } = await api.get(`/api/v1/exchange-connections/${id}`)
    detail.value = data
  } finally {
    detailLoading.value = false
  }
}


async function doDelete () {
  if (!detail.value?.id) return
  deleteLoading.value = true
  try {
    await api.delete(`/api/v1/exchange-connections/${detail.value.id}`)
    confirmDelete.value = false

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
    const { data } = await api.get('/api/v1/exchange-connections/types')
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
      name: createForm.name.trim(),
      apiKey: '',
      apiSecret: '',
      passphrase: null
    }

    const { data: id } = await api.post('/api/v1/exchange-connections', payload)

    createDialog.value = false

    // 1) перезагрузить список
    await loadList()

    // 2) выбрать созданную запись (и подгрузить детали)
    await select(id)

  } catch (e) {
    Notify.create({ type: 'negative', message: 'Не удалось создать подключение' + e })
  } finally {
    createLoading.value = false
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
</style>
