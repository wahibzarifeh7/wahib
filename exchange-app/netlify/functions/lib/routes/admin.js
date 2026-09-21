const { query, withTransaction } = require('../db');
const { hashPassword } = require('../auth');
const { mapUser, mapLoginLog, mapDayHistory } = require('../mappers');
const { computeAdjustmentTotals } = require('../ledger');
const { ValidationError } = require('../errors');

async function getAdminState() {
  const [usersRes, loginRes, dayHistRes, ledgerRes] = await Promise.all([
    query('select * from users order by created_at asc'),
    query('select * from login_log order by at desc limit 30'),
    query('select * from day_history order by closed_at desc'),
    query('select * from ledger_state where id = 1'),
  ]);
  const { rows: adjRows } = await query('select * from adjustments where at >= $1 order by at desc', [ledgerRes.rows[0].day_start_started_at]);
  const todayAdjustments = adjRows.map((r) => ({ id: r.id, currency: r.currency, direction: r.direction, amount: Number(r.amount), reason: r.reason, by: r.by, at: r.at }));

  return {
    users: usersRes.rows.map(mapUser),
    loginLog: loginRes.rows.map(mapLoginLog),
    dayHistory: dayHistRes.rows.map(mapDayHistory),
    todayAdjustments,
    adjustmentTotals: computeAdjustmentTotals(todayAdjustments),
  };
}

// Empty/missing means "no email". Anything else must look like an address.
function normalizeEmail(value) {
  const email = String(value == null ? '' : value).trim();
  if (!email) return null;
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    throw new ValidationError('Enter a valid email address.');
  }
  return email;
}

function uniqueViolation(err) {
  if (!err || err.code !== '23505') return null;
  return String(err.constraint || '').includes('email')
    ? new ValidationError('That email is already used by another account.')
    : new ValidationError('That username is already taken.');
}

async function createUser(actor, payload) {
  const username = String(payload.username || '').trim();
  const password = String(payload.password || '');
  const role = payload.role === 'admin' ? 'admin' : 'staff';
  const email = normalizeEmail(payload.email);
  if (username.length < 3) throw new ValidationError('Username must be at least 3 characters.');
  if (password.length < 4) throw new ValidationError('Password must be at least 4 characters.');

  const hash = await hashPassword(password);
  try {
    const { rows } = await query(
      'insert into users (username, password_hash, role, email) values ($1,$2,$3,$4) returning *',
      [username, hash, role, email]
    );
    return mapUser(rows[0]);
  } catch (err) {
    throw uniqueViolation(err) || err;
  }
}

async function setEmail(actor, id, payload) {
  const email = normalizeEmail(payload.email);
  try {
    const { rows } = await query('update users set email = $1 where id = $2 returning *', [email, id]);
    if (rows.length === 0) throw new ValidationError('Account not found.', 404);
    return mapUser(rows[0]);
  } catch (err) {
    throw uniqueViolation(err) || err;
  }
}

async function toggleRole(actor, id) {
  return withTransaction(async (client) => {
    const { rows } = await client.query('select * from users where id = $1 for update', [id]);
    if (rows.length === 0) throw new ValidationError('Account not found.', 404);
    const target = rows[0];

    if (target.role === 'admin') {
      const { rows: adminCount } = await client.query("select count(*)::int as c from users where role = 'admin'");
      if (adminCount[0].c <= 1) throw new ValidationError('At least one admin account is required.');
    }
    const nextRole = target.role === 'admin' ? 'staff' : 'admin';
    const { rows: updated } = await client.query('update users set role = $1 where id = $2 returning *', [nextRole, id]);
    return mapUser(updated[0]);
  });
}

async function deleteUser(actor, id) {
  if (id === actor.id) throw new ValidationError('You cannot delete your own account.');
  return withTransaction(async (client) => {
    const { rows } = await client.query('select * from users where id = $1 for update', [id]);
    if (rows.length === 0) throw new ValidationError('Account not found.', 404);
    const target = rows[0];

    if (target.role === 'admin') {
      const { rows: adminCount } = await client.query("select count(*)::int as c from users where role = 'admin'");
      if (adminCount[0].c <= 1) throw new ValidationError('At least one admin account is required.');
    }
    await client.query('delete from users where id = $1', [id]);
    return { ok: true };
  });
}

async function resetPassword(actor, id, payload) {
  const password = String(payload.password || '');
  if (password.length < 4) throw new ValidationError('Password must be at least 4 characters.');
  const hash = await hashPassword(password);
  const { rows } = await query('update users set password_hash = $1 where id = $2 returning *', [hash, id]);
  if (rows.length === 0) throw new ValidationError('Account not found.', 404);
  // A changed password must also end any session opened with the old one — except
  // the admin's own, so changing your own password doesn't sign you out mid-action.
  if (id !== actor.id) await query('delete from sessions where user_id = $1', [id]);
  return mapUser(rows[0]);
}

async function factoryReset(actor, payload) {
  const confirm = String(payload.confirm || '').trim().toUpperCase();
  if (confirm !== 'RESET') throw new ValidationError('Type RESET (in capitals) to confirm.');

  return withTransaction(async (client) => {
    await client.query('select * from ledger_state where id = 1 for update');
    await client.query('delete from transactions');
    await client.query('delete from rate_history');
    await client.query('delete from day_history');
    await client.query('delete from adjustments');
    await client.query('delete from login_log');
    await client.query('update rates set buy = 14850, sell = 15050, updated_at = now(), updated_by = $1 where id = 1', [actor.username]);
    await client.query(
      `update ledger_state
       set reserves_usd = 5000, reserves_syp = 60000000, day_start_usd = 5000, day_start_syp = 60000000,
           day_start_started_at = now(), day_start_started_by = $1
       where id = 1`,
      [actor.username]
    );
    return { ok: true };
  });
}

module.exports = { getAdminState, createUser, setEmail, toggleRole, deleteUser, resetPassword, factoryReset };
