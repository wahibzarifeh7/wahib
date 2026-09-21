const { query, withTransaction } = require('../db');
const { verifyPassword, hashPassword, generateToken, hashToken, isLockedOut, recordFailedAttempt, resetFailedAttempts } = require('../auth');
const { mapUser } = require('../mappers');
const { ValidationError } = require('../errors');
const mail = require('../mail');

const MIN_PASSWORD_LENGTH = 4;
const RESET_TOKEN_MINUTES = 30;
const MAX_RESET_REQUESTS_PER_HOUR = 3;

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

// The link's base URL comes from config, never from the request's Host header —
// otherwise anyone could request a reset for a victim with a forged Host and get
// the victim's real token mailed out pointing at an attacker's site.
function appUrl() {
  return (process.env.APP_URL || 'https://wzexchange.netlify.app').replace(/\/+$/, '');
}

// Always answers the same way whether or not the account exists or has an email
// on file, so this endpoint can't be used to discover which usernames/emails are
// registered. The only distinct error is "recovery isn't configured", which says
// nothing about any account.
async function requestPasswordReset(payload) {
  if (!mail.isConfigured()) {
    throw new ValidationError('Password recovery is not set up yet. Ask an administrator to reset your password.', 503);
  }
  const identifier = String(payload.identifier || '').trim();
  if (!identifier) throw new ValidationError('Enter your username or email.');

  const generic = { ok: true };
  const { rows } = await query(
    'select id, username, email from users where email = $1 or username = $1 order by coalesce(email = $1, false) desc limit 1',
    [identifier]
  );
  if (rows.length === 0 || !rows[0].email) return generic;
  const user = rows[0];

  const { rows: recent } = await query(
    "select count(*)::int as c from password_resets where user_id = $1 and created_at > now() - interval '1 hour'",
    [user.id]
  );
  if (recent[0].c >= MAX_RESET_REQUESTS_PER_HOUR) return generic;

  const token = generateToken();
  await query(
    `insert into password_resets (user_id, token_hash, expires_at)
     values ($1, $2, now() + make_interval(mins => $3))`,
    [user.id, hashToken(token), RESET_TOKEN_MINUTES]
  );

  // Fragment (#) rather than query string: it never reaches server logs or Referer headers.
  const link = `${appUrl()}/#reset=${token}`;
  const text =
    `Hi ${user.username},\n\n` +
    `We received a request to reset your WZExchange password. Your username is: ${user.username}\n\n` +
    `Use this link within ${RESET_TOKEN_MINUTES} minutes to choose a new password:\n${link}\n\n` +
    `If you didn't ask for this, ignore this email — your password won't change.`;
  const html =
    `<p>Hi ${escapeHtml(user.username)},</p>` +
    `<p>We received a request to reset your WZExchange password. Your username is: <b>${escapeHtml(user.username)}</b></p>` +
    `<p><a href="${link}">Choose a new password</a> (valid for ${RESET_TOKEN_MINUTES} minutes)</p>` +
    `<p style="color:#666">If you didn't ask for this, ignore this email — your password won't change.</p>`;

  try {
    await mail.sendMail({ to: user.email, subject: 'Reset your WZExchange password', text, html });
  } catch (err) {
    // Log the failure for us, but answer the same as success (see above).
    console.error('Password reset email failed:', err && err.message);
  }
  return generic;
}

async function resetPassword(payload) {
  const token = String(payload.token || '');
  const password = String(payload.password || '');
  const invalid = () => new ValidationError('This reset link is invalid or has expired. Request a new one.');
  if (!token) throw invalid();
  if (password.length < MIN_PASSWORD_LENGTH) {
    throw new ValidationError(`Password must be at least ${MIN_PASSWORD_LENGTH} characters.`);
  }

  return withTransaction(async (client) => {
    const { rows } = await client.query(
      'select id, user_id from password_resets where token_hash = $1 and used_at is null and expires_at > now() for update',
      [hashToken(token)]
    );
    if (rows.length === 0) throw invalid();
    const userId = rows[0].user_id;

    const hash = await hashPassword(password);
    await client.query(
      'update users set password_hash = $1, failed_attempts = 0, locked_until = null where id = $2',
      [hash, userId]
    );
    // One-time use: burn this token and any other outstanding ones for the account,
    // and sign out every device that had the old password.
    await client.query('update password_resets set used_at = now() where user_id = $1 and used_at is null', [userId]);
    await client.query('delete from sessions where user_id = $1', [userId]);
    return { ok: true };
  });
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

module.exports = { login, logout, requestPasswordReset, resetPassword };
