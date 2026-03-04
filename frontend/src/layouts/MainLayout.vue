<template>
  <q-layout view="hHh lpR fFf">

    <q-header reveal elevated class="bg-primary text-white" height-hint="98">
      <q-toolbar>
        <q-toolbar-title>
          <q-avatar>
            <img src="https://cdn.quasar.dev/logo-v2/svg/logo-mono-white.svg">
          </q-avatar>
          Title
        </q-toolbar-title>
      </q-toolbar>

      <q-tabs align="left">
        <q-route-tab to="/" label="Page One" />
        <q-route-tab to="/bots" label="Боты" />
        <q-route-tab to="/exchanges" label="Биржи" />
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
// (Boot file already registers it globally; this is an extra safety net and makes the dependency explicit at the layout level.)
provide('toast', toast)
</script>
