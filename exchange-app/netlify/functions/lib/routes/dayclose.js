const { withTransaction } = require('../db');
const { mapLedgerState, mapTransaction, mapDayHistory } = require('../mappers');
const { computeDayStats, round2 } = require('../ledger');
const { ValidationError } = require('../errors');

async function setOpening(user, payload) {
  const usd = Number(payload.usd);
  const syp = Number(payload.syp);
  if (!(usd >= 0) || !(syp >= 0)) throw new ValidationError('Enter valid non-negative amounts.');

  return withTransaction(async (client) => {
    await client.query('select * from ledger_state where id = 1 for update');
    const { rows } = await client.query(
      `update ledger_state
       set reserves_usd=$1, reserves_syp=$2, day_start_usd=$1, day_start_syp=$2, day_start_started_at=now(), day_start_started_by=$3
       where id = 1 returning *`,
      [usd, syp, user.username]
    );
    return mapLedgerState(rows[0]);
  });
}

async function adjustReserve(user, payload) {
  const currency = payload.currency === 'syp' ? 'syp' : payload.currency === 'usd' ? 'usd' : null;
  const direction = payload.direction === 'remove' ? 'remove' : payload.direction === 'add' ? 'add' : null;
  const amount = Number(payload.amount);
  const reason = payload.reason ? String(payload.reason).slice(0, 80) : null;
  if (!currency || !direction) throw new ValidationError('Invalid adjustment.');
  if (!(amount > 0)) throw new ValidationError('Enter an amount greater than zero.');

  return withTransaction(async (client) => {
    const { rows } = await client.query('select * from ledger_state where id = 1 for update');
    const ledger = mapLedgerState(rows[0]);
    const field = currency === 'usd' ? 'reservesUsd' : 'reservesSyp';
    const current = ledger[field];

    if (direction === 'remove' && amount > current) {
      throw new ValidationError(`Not enough ${currency.toUpperCase()} in reserve to remove that much (${current} available).`);
    }
    const next = round2(direction === 'add' ? current + amount : current - amount);
    const column = currency === 'usd' ? 'reserves_usd' : 'reserves_syp';

    await client.query(`update ledger_state set ${column} = $1 where id = 1`, [next]);
    const { rows: adjRows } = await client.query(
      'insert into adjustments (currency, direction, amount, reason, "by", at) values ($1,$2,$3,$4,$5, now()) returning *',
      [currency, direction, amount, reason, user.username]
    );

    return {
      ledgerState: { ...ledger, [field]: next },
      adjustment: { id: adjRows[0].id, currency, direction, amount, reason, by: user.username, at: adjRows[0].at },
    };
  });
}

async function closeDay(user) {
  return withTransaction(async (client) => {
    const { rows: ledgerRows } = await client.query('select * from ledger_state where id = 1 for update');
    const ledger = mapLedgerState(ledgerRows[0]);
    const { rows: txnRows } = await client.query('select * from transactions where at >= $1', [ledger.dayStartStartedAt]);
    const stats = computeDayStats(txnRows.map(mapTransaction));

    const { rows: dayRows } = await client.query(
      `insert into day_history
        (closed_at, closed_by, opened_at, open_usd, open_syp, close_usd, close_syp, buy_volume_usd, sell_volume_usd, txn_count, profit)
       values (now(), $1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
       returning *`,
      [user.username, ledger.dayStartStartedAt, ledger.dayStartUsd, ledger.dayStartSyp, ledger.reservesUsd, ledger.reservesSyp,
        stats.buyVolume, stats.sellVolume, stats.count, stats.profit]
    );

    const { rows: updated } = await client.query(
      `update ledger_state
       set day_start_usd = reserves_usd, day_start_syp = reserves_syp, day_start_started_at = now(), day_start_started_by = $1
       where id = 1 returning *`,
      [user.username]
    );

    return { ledgerState: mapLedgerState(updated[0]), dayHistoryEntry: mapDayHistory(dayRows[0]) };
  });
}

module.exports = { setOpening, adjustReserve, closeDay };
