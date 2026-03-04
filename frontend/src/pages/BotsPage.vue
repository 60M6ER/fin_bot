<template>
  <q-page class="q-pa-md">
    <div class="text-h5 q-mb-md">Боты</div>

    <q-btn
      label="Обновить"
      color="primary"
      @click="loadBots"
    />

    <q-list bordered separator class="q-mt-md">
      <q-item v-for="bot in bots" :key="bot.id">
        <q-item-section>
          {{ bot.name }}
        </q-item-section>
      </q-item>
    </q-list>
  </q-page>
</template>

<script setup>
import { ref } from 'vue'
import { apiClient, getErrorMessage } from 'src/services/apiClient'

const bots = ref([])

const loadBots = async () => {
  try {
    bots.value = await apiClient.get('/api/v1/bots')
  } catch (e) {
    console.error(getErrorMessage(e, 'Не удалось загрузить ботов'))
  }
}
</script>
