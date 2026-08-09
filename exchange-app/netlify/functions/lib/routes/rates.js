const { query, withTransaction } = require('../db');
const { mapRates, mapRateHistory, mapLedgerState, mapTransaction } = require('../mappers');
const { computeDayStats } = require('../ledger');
const { ValidationError } = require('../errors');

async function getState() {
  const [ratesRes, ledgerRes, txnsRes, historyRes, countRes] = await Promise.all([
    query('select * from rates where id = 1'),
    query('select * from ledger_state where id = 1'),
    query('select * from transactions order by at desc limit 25'),
    query('select * from rate_history order by changed_at desc limit 100'),
    query('select count(*)::int as c from transactions'),
  ]);
  const ledgerState = mapLedgerState(ledgerRes.rows[0]);
  const { rows: todayRows } = await query('select * from transactions where at >= $1', [ledgerRes.rows[0].day_start_started_at]);

  return {
    rates: mapRates(ratesRes.rows[0]),
    ledgerState,
    todayStats: computeDayStats(todayRows.map(mapTransaction)),
    recentTransactions: txnsRes.rows.map(mapTransaction),
    totalTransactionCount: countRes.rows[0].c,
    rateHistory: historyRes.rows.map(mapRateHistory),
  };
}

async function updateRates(user, payload) {
  const buy = Number(payload.buy);
  const sell = Number(payload.sell);
  if (!(buy > 0) || !(sell > 0)) throw new ValidationError('Both rates must be positive numbers.');
  if (sell < buy) throw new ValidationError('Sell rate should not be lower than buy rate.');

  return withTransaction(async (client) => {
    const { rows } = await client.query('select * from rates where id = 1 for update');
    const current = rows[0];
    await client.query(
      'insert into rate_history (prev_buy, prev_sell, buy, sell, changed_by, changed_at) values ($1,$2,$3,$4,$5, now())',
      [current.buy, current.sell, buy, sell, user.username]
    );
    const { rows: updated } = await client.query(
      'update rates set buy = $1, sell = $2, updated_at = now(), updated_by = $3 where id = 1 returning *',
      [buy, sell, user.username]
    );
    return mapRates(updated[0]);
  });
}

module.exports = { getState, updateRates };
