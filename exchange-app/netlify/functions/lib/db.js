const { Pool } = require('pg');

// Reused across warm invocations. Kept small — this app is a small internal
// team tool, not high-traffic. Uses the Supabase *pooled* (pgbouncer,
// transaction-mode) connection string via DATABASE_URL.
//
// Do NOT switch `pg` to named prepared statements — pgbouncer transaction
// mode hands out a different backend connection per transaction, so a
// prepared statement "named" on one backend won't exist on the next. Plain
// parameterized queries (the default, unnamed "Parse" per call) are fine.
let pool = null;

function getPool() {
  if (!pool) {
    const connectionString = process.env.DATABASE_URL;
    if (!connectionString) throw new Error('DATABASE_URL is not set');
    pool = new Pool({
      connectionString,
      max: 3,
      ssl: { rejectUnauthorized: false },
    });
  }
  return pool;
}

// Runs fn(client) inside BEGIN/COMMIT, ROLLBACK on throw. fn may call
// client.query('SELECT ... FOR UPDATE') itself when it needs the ledger lock.
async function withTransaction(fn) {
  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    try { await client.query('ROLLBACK'); } catch (_) { /* ignore */ }
    throw err;
  } finally {
    client.release();
  }
}

async function query(text, params) {
  return getPool().query(text, params);
}

// pg returns `numeric` columns as strings to avoid silent precision loss.
// Every DB row that feeds the ported ledger math must go through this.
function num(v) { return v == null ? v : Number(v); }

module.exports = { getPool, withTransaction, query, num };
