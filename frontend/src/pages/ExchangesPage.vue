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
              v-if="detail"
              dense
              color="primary"
              :label="detail.active ? 'Остановить' : 'Запустить'"
              :loading="actionLoading || desiredActive !== null"
              :disable="detailLoading || actionLoading || desiredActive !== null"
              @click="doActivateDeactivate"
            />

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
                  Runtime: <span class="mono">{{ runtime?.state || '—' }}</span>
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

                  <div class="col-12 col-md-6">
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

const toast = inject('toast')

const list = ref([])
const listLoading = ref(false)

const selectedId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)

// runtime status (in-memory state on backend)
const runtime = ref(null)
const runtimeLoading = ref(false)
const actionLoading = ref(false)
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


function normalizeStatus (s) {
  return String(s || '').toUpperCase()
}

function isRuntimeActive (rt) {
  const st = normalizeStatus(rt?.state)
  // tolerate different enums/strings
  return ['ACTIVE', 'RUNNING', 'CONNECTED', 'ONLINE', 'STARTED'].includes(st)
}

function isRuntimeInactive (rt) {
  const st = normalizeStatus(rt?.state)
  return ['INACTIVE', 'STOPPED', 'DISCONNECTED', 'OFFLINE', 'IDLE'].includes(st)
}

async function loadRuntime (id) {
  if (!id) return
  runtimeLoading.value = true
  try {
    runtime.value = await apiClient.get(`/api/v1/exchange-connections/${id}/runtime`)
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

      const ok = targetActive ? isRuntimeActive(runtime.value) : isRuntimeInactive(runtime.value)
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
    loadRuntime(id)
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
    if (selectedId.value === id) {
      await loadRuntime(id)
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
</style>
