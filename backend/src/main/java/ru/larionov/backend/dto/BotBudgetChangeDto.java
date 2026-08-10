package ru.larionov.backend.dto;

import java.math.BigDecimal;

/**
 * Ответ на изменение бюджета: что было, что стало и сколько денег этим освобождено.
 *
 * @param previousBudget  бюджет до изменения
 * @param budget          новый бюджет из настроек
 * @param workingBudget   он же с учётом реинвестированной прибыли — по нему и считаются
 *                        размеры заявок
 * @param committed       уже занятые деньги: себестоимость открытой позиции плюс
 *                        зарезервированное выставленными покупками
 * @param free            рабочий бюджет минус занятое. Именно столько бот больше не
 *                        трогает — но физически на бирже эти деньги освободятся лишь
 *                        по мере снятия и переразмера покупок
 * @param positionCost    себестоимость открытой позиции: ниже неё бюджет не опускается,
 *                        уже купленное обратно не раскупить
 * @param appliedLive     применено ли на лету. false означает «сохранено, подействует
 *                        при следующем запуске»
 */
public record BotBudgetChangeDto(
        BigDecimal previousBudget,
        BigDecimal budget,
        BigDecimal workingBudget,
        BigDecimal committed,
        BigDecimal free,
        BigDecimal positionCost,
        boolean appliedLive
) {}
