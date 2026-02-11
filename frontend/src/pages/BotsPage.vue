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
import axios from 'axios'

const bots = ref([])

const loadBots = async () => {
  const response = await axios.get('/api/v1/bots')
  bots.value = response.data
}
</script>
