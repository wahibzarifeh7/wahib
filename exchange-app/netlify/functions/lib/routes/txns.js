const { withTransaction } = require('../db');
const { mapTransaction, mapLedgerState } = require('../mappers');
const { recalcTodayLedger, round2 } = require('../ledger');
const { ValidationError } = require('../errors');

async function executeTransaction(user, payload) {
  const type = payload.type === 'sell' ? 'sell' : payload.type === 'buy' ? 'buy' : null;
  const amountUsd = Number(payload.amountUsd);
  const note = payload.note ? String(payload.note).slice(0, 60) : null;
  if (!type) throw new ValidationError('Invalid transaction type.');
  if (!(amountUsd > 0)) throw new ValidationError('Enter an amount greater than zero.');

  return withTransaction(async (client) => {
    const { rows: rateRows } = await client.query('select * from rates where id = 1');
    const rate = type === 'buy' ? Number(rateRows[0].buy) : Number(rateRows[0].sell);
    const amountSyp = round2(amountUsd * rate);

    const { rows: ledgerRows } = await client.query('select * from ledger_state where id = 1 for update');
    const ledger = mapLedgerState(ledgerRows[0]);

    if (type === 'buy' && amountSyp > ledger.reservesSyp) {
      throw new ValidationError(`Not enough SYP in reserve to complete this trade (${amountSyp} needed).`);
    }
    if (type === 'sell' && amountUsd > ledger.reservesUsd) {
      throw new ValidationError(`Not enough USD in reserve to complete this trade (${amountUsd} needed).`);
    }

    const newUsd = round2(type === 'buy' ? ledger.reservesUsd + amountUsd : ledger.reservesUsd - amountUsd);
    const newSyp = round2(type === 'buy' ? ledger.reservesSyp - amountSyp : ledger.reservesSyp + amountSyp);

    const { rows: inserted } = await client.query(
      `insert into transactions
        (type, amount_usd, rate_applied, buy_rate_at_time, sell_rate_at_time, amount_syp, usd_after, syp_after, operator, note, at)
       values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10, now())
       returning *`,
      [type, amountUsd, rate, Number(rateRows[0].buy), Number(rateRows[0].sell), amountSyp, newUsd, newSyp, user.username, note]
    );
    await client.query('update ledger_state set reserves_usd = $1, reserves_syp = $2 where id = 1', [newUsd, newSyp]);

    return { transaction: mapTransaction(inserted[0]), ledgerState: { ...ledger, reservesUsd: newUsd, reservesSyp: newSyp } };
  });
}

async function loadTodayLedgerBundle(client, forUpdate) {
  const { rows: ledgerRows } = await client.query(`select * from ledger_state where id = 1${forUpdate ? ' for update' : ''}`);
  const ledger = mapLedgerState(ledgerRows[0]);
  const { rows: txnRows } = await client.query('select * from transactions where at >= $1 order by at asc', [ledger.dayStartStartedAt]);
  const { rows: adjRows } = await client.query('select * from adjustments where at >= $1 order by at asc', [ledger.dayStartStartedAt]);
  return {
    ledger,
    transactions: txnRows.map(mapTransaction),
    adjustments: adjRows.map((r) => ({ id: r.id, currency: r.currency, direction: r.direction, amount: Number(r.amount), at: r.at })),
  };
}

async function editTransaction(user, id, payload) {
  const type = payload.type === 'sell' ? 'sell' : payload.type === 'buy' ? 'buy' : null;
  const amountUsd = Number(payload.amountUsd);
  const rate = Number(payload.rate);
  const note = payload.note ? String(payload.note).slice(0, 60) : null;
  if (!type) throw new ValidationError('Invalid transaction type.');
  if (!(amountUsd > 0) || !(rate > 0)) throw new ValidationError('Amount and rate must be greater than zero.');

  return withTransaction(async (client) => {
    const bundle = await loadTodayLedgerBundle(client, true);
    const target = bundle.transactions.find((t) => t.id === id);
    if (!target) throw new ValidationError('Transaction not found or is from a closed day and cannot be modified.', 404);

    target.type = type;
    target.amountUsd = amountUsd;
    target.rateApplied = rate;
    target.amountSyp = round2(amountUsd * rate);
    target.note = note;
    if (type === 'buy') target.buyRateAtTime = rate; else target.sellRateAtTime = rate;
    target.editedAt = new Date().toISOString();
    target.editedBy = user.username;

    const result = recalcTodayLedger(bundle.ledger, bundle.transactions, bundle.adjustments);
    if (result.reservesUsd < -0.005 || result.reservesSyp < -0.5) {
      throw new ValidationError('This change would leave reserves negative — adjust the amount and try again.');
    }

    for (const t of bundle.transactions) {
      await client.query(
        `update transactions set type=$2, amount_usd=$3, rate_applied=$4, buy_rate_at_time=$5, sell_rate_at_time=$6,
           amount_syp=$7, usd_after=$8, syp_after=$9, note=$10, edited_at=$11, edited_by=$12
         where id = $1`,
        [t.id, t.type, t.amountUsd, t.rateApplied, t.buyRateAtTime, t.sellRateAtTime, t.amountSyp, t.usdAfter, t.sypAfter, t.note, t.editedAt || null, t.editedBy || null]
      );
    }
    await client.query('update ledger_state set reserves_usd = $1, reserves_syp = $2 where id = 1', [result.reservesUsd, result.reservesSyp]);

    return { ledgerState: { ...bundle.ledger, ...result } };
  });
}

async function deleteTransaction(user, id) {
  return withTransaction(async (client) => {
    const bundle = await loadTodayLedgerBundle(client, true);
    const target = bundle.transactions.find((t) => t.id === id);
    if (!target) throw new ValidationError('Transaction not found or is from a closed day and cannot be modified.', 404);

    const remaining = bundle.transactions.filter((t) => t.id !== id);
    const result = recalcTodayLedger(bundle.ledger, remaining, bundle.adjustments);
    if (result.reservesUsd < -0.005 || result.reservesSyp < -0.5) {
      throw new ValidationError('Cannot delete: this would leave reserves negative.');
    }

    await client.query('delete from transactions where id = $1', [id]);
    for (const t of remaining) {
      await client.query('update transactions set usd_after = $2, syp_after = $3 where id = $1', [t.id, t.usdAfter, t.sypAfter]);
    }
    await client.query('update ledger_state set reserves_usd = $1, reserves_syp = $2 where id = 1', [result.reservesUsd, result.reservesSyp]);

    return { ledgerState: { ...bundle.ledger, ...result } };
  });
}

module.exports = { executeTransaction, editTransaction, deleteTransaction };
