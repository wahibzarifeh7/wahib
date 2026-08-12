const { num } = require('./db');

function mapUser(r) {
  return {
    id: r.id, username: r.username, role: r.role,
    createdAt: r.created_at, lastLoginAt: r.last_login_at,
  };
}

function mapRates(r) {
  return { buy: num(r.buy), sell: num(r.sell), updatedAt: r.updated_at, updatedBy: r.updated_by };
}

function mapRateHistory(r) {
  return {
    id: r.id, prevBuy: num(r.prev_buy), prevSell: num(r.prev_sell),
    buy: num(r.buy), sell: num(r.sell), changedBy: r.changed_by, changedAt: r.changed_at,
  };
}

function mapLedgerState(r) {
  return {
    reservesUsd: num(r.reserves_usd), reservesSyp: num(r.reserves_syp),
    dayStartUsd: num(r.day_start_usd), dayStartSyp: num(r.day_start_syp),
    dayStartStartedAt: r.day_start_started_at, dayStartStartedBy: r.day_start_started_by,
  };
}

function mapTransaction(r) {
  return {
    id: r.id, type: r.type, amountUsd: num(r.amount_usd), rateApplied: num(r.rate_applied),
    buyRateAtTime: num(r.buy_rate_at_time), sellRateAtTime: num(r.sell_rate_at_time),
    amountSyp: num(r.amount_syp), usdAfter: num(r.usd_after), sypAfter: num(r.syp_after),
    operator: r.operator, note: r.note, at: r.at, editedAt: r.edited_at, editedBy: r.edited_by,
  };
}

function mapAdjustment(r) {
  return {
    id: r.id, currency: r.currency, direction: r.direction, amount: num(r.amount),
    reason: r.reason, by: r.by, at: r.at,
  };
}

function mapDayHistory(r) {
  return {
    id: r.id, closedAt: r.closed_at, closedBy: r.closed_by, openedAt: r.opened_at,
    openUsd: num(r.open_usd), openSyp: num(r.open_syp), closeUsd: num(r.close_usd), closeSyp: num(r.close_syp),
    buyVolumeUsd: num(r.buy_volume_usd), sellVolumeUsd: num(r.sell_volume_usd),
    txnCount: r.txn_count, profit: num(r.profit),
  };
}

function mapLoginLog(r) { return { id: r.id, username: r.username, at: r.at }; }

module.exports = { mapUser, mapRates, mapRateHistory, mapLedgerState, mapTransaction, mapAdjustment, mapDayHistory, mapLoginLog };
