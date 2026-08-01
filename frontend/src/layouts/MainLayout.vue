<template>
  <q-layout view="hHh lpR fFf">

    <q-header elevated class="bg-primary text-white">
      <q-toolbar>
        <q-toolbar-title class="row items-center q-gutter-sm">
          <q-icon name="candlestick_chart" size="sm" />
          <span>Торговый бот</span>
        </q-toolbar-title>
      </q-toolbar>

      <q-tabs align="left">
        <q-route-tab to="/" label="Обзор" exact />
        <q-route-tab to="/bots" label="Боты" />
        <q-route-tab to="/exchanges" label="Биржи" />
        <q-route-tab to="/settings" label="Настройки" />
      </q-tabs>
    </q-header>

    <q-page-container>
      <router-view />
    </q-page-container>

  </q-layout>
</template>

<script setup>
import { provide } from 'vue'
import { toast } from 'boot/notify'

// Ensure toast is available via inject('toast') for all nested pages/components.
provide('toast', toast)
</script>
