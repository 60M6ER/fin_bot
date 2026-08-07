<template>
  <!--
    Подсказка привязывается к родительскому элементу, поэтому компонент ставится
    ВНУТРЬ ячейки поля, а не рядом с ней: наведение на любую часть поля показывает
    расшифровку.

    Появляется с задержкой: без неё подсказки вспыхивали бы одна за другой, пока
    курсор просто пересекает форму по дороге к нужному полю.
  -->
  <q-tooltip
    anchor="top middle"
    self="bottom middle"
    :delay="350"
    :offset="[0, 8]"
    max-width="420px"
    class="field-hint"
  >
    {{ text }}
  </q-tooltip>
</template>

<script setup>
defineProps({
  text: { type: String, required: true }
})
</script>

<style scoped>
.field-hint {
  font-size: 12.5px;
  line-height: 1.5;
  padding: 8px 10px;
}
</style>
