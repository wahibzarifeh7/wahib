// Ported near-verbatim from the original client-side app (exchange-app/index.html,
// recalcTodayLedger / computeDayStats). Operates on plain JS objects — callers are
// responsible for loading rows from Postgres (via db.num() for numeric columns) and
// writing the results back inside the same locked transaction.

function round2(n) { return Number(Number(n).toFixed(2)); }

// ledgerState: { reservesUsd, reservesSyp, dayStartUsd, dayStartSyp, dayStartStartedAt }
// transactions/adjustments: arrays already filtered to `at >= dayStartStartedAt`, each
// transaction object will have usdAfter/sypAfter set in place as a side effect.
// Returns { reservesUsd, reservesSyp } — the final balances after replaying every event
// in chronological order from the day-start anchor.
function recalcTodayLedger(ledgerState, transactions, adjustments) {
  const events = [];
  transactions.forEach((t) => events.push({ at: t.at, kind: 'txn', ref: t }));
  adjustments.forEach((a) => events.push({ at: a.at, kind: 'adj', ref: a }));
  events.sort((a, b) => (a.at < b.at ? -1 : a.at > b.at ? 1 : 0));

  let usd = ledgerState.dayStartUsd;
  let syp = ledgerState.dayStartSyp;

  events.forEach((e) => {
    if (e.kind === 'txn') {
      const t = e.ref;
      if (t.type === 'buy') { usd += t.amountUsd; syp -= t.amountSyp; }
      else { usd -= t.amountUsd; syp += t.amountSyp; }
      usd = round2(usd);
      syp = round2(syp);
      t.usdAfter = usd;
      t.sypAfter = syp;
    } else {
      const a = e.ref;
      const signed = a.direction === 'add' ? a.amount : -a.amount;
      if (a.currency === 'usd') usd = round2(usd + signed);
      else syp = round2(syp + signed);
    }
  });

  return { reservesUsd: usd, reservesSyp: syp };
}

function computeDayStats(transactions) {
  let buyVolume = 0, sellVolume = 0, profit = 0;
  transactions.forEach((t) => {
    if (t.type === 'buy') buyVolume += t.amountUsd;
    else {
      sellVolume += t.amountUsd;
      profit += (t.sellRateAtTime - t.buyRateAtTime) * t.amountUsd;
    }
  });
  return { buyVolume: round2(buyVolume), sellVolume: round2(sellVolume), profit: round2(profit), count: transactions.length };
}

function computeAdjustmentTotals(adjustments) {
  let usd = 0, syp = 0;
  adjustments.forEach((a) => {
    const signed = a.direction === 'add' ? a.amount : -a.amount;
    if (a.currency === 'usd') usd += signed; else syp += signed;
  });
  return { usd: round2(usd), syp: round2(syp) };
}

module.exports = { round2, recalcTodayLedger, computeDayStats, computeAdjustmentTotals };
