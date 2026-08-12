const { query } = require('../db');
const { verifyPassword, generateToken, hashToken, isLockedOut, recordFailedAttempt, resetFailedAttempts } = require('../auth');
const { mapUser } = require('../mappers');
const { ValidationError } = require('../errors');

async function login(payload) {
  const username = String(payload.username || '').trim();
  const password = String(payload.password || '');
  if (!username || !password) throw new ValidationError('Incorrect username or password.', 401);

  const { rows } = await query('select * from users where username = $1', [username]);
  if (rows.length === 0) throw new ValidationError('Incorrect username or password.', 401);
  const userRow = rows[0];

  if (await isLockedOut(userRow)) {
    throw new ValidationError('Too many failed attempts. Try again in a minute.', 429);
  }

  const valid = await verifyPassword(password, userRow.password_hash);
  if (!valid) {
    await recordFailedAttempt(userRow.id);
    throw new ValidationError('Incorrect username or password.', 401);
  }
  await resetFailedAttempts(userRow.id);

  const token = generateToken();
  const tokenHash = hashToken(token);
  await query(
    `insert into sessions (user_id, token_hash, created_at)
     values ($1, $2, now())
     on conflict (user_id) do update set token_hash = excluded.token_hash, created_at = now()`,
    [userRow.id, tokenHash]
  );
  await query('update users set last_login_at = now() where id = $1', [userRow.id]);
  await query('insert into login_log (user_id, username, at) values ($1, $2, now())', [userRow.id, userRow.username]);

  return { token, user: mapUser({ ...userRow, last_login_at: new Date() }) };
}

async function logout(user) {
  await query('delete from sessions where user_id = $1', [user.id]);
  return { ok: true };
}

module.exports = { login, logout };
