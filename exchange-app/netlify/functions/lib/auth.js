const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const { query } = require('./db');

const BCRYPT_ROUNDS = 10;
const MAX_FAILED_ATTEMPTS = 5;
const LOCKOUT_MS = 60 * 1000;

function hashPassword(password) { return bcrypt.hash(password, BCRYPT_ROUNDS); }
function verifyPassword(password, hash) { return bcrypt.compare(password, hash); }

function generateToken() { return crypto.randomBytes(32).toString('base64url'); }
function hashToken(token) { return crypto.createHash('sha256').update(token).digest('hex'); }

function extractToken(event) {
  const header = (event.headers && (event.headers.authorization || event.headers.Authorization)) || '';
  if (!header.startsWith('Bearer ')) return null;
  return header.slice(7).trim() || null;
}

// Returns { id, username, role } for a valid session, or null.
async function authenticate(event) {
  const token = extractToken(event);
  if (!token) return null;
  const tokenHash = hashToken(token);
  const { rows } = await query(
    `select u.id, u.username, u.role
     from sessions s join users u on u.id = s.user_id
     where s.token_hash = $1`,
    [tokenHash]
  );
  if (rows.length === 0) return null;
  query('update sessions set last_seen_at = now() where token_hash = $1', [tokenHash]).catch(() => {});
  return rows[0];
}

function isAdmin(user) { return !!user && user.role === 'admin'; }

async function isLockedOut(userRow) {
  return !!(userRow.locked_until && new Date(userRow.locked_until).getTime() > Date.now());
}

async function recordFailedAttempt(userId) {
  const { rows } = await query(
    `update users
     set failed_attempts = failed_attempts + 1,
         locked_until = case when failed_attempts + 1 >= $2 then now() + make_interval(secs => $3) else locked_until end
     where id = $1
     returning failed_attempts, locked_until`,
    [userId, MAX_FAILED_ATTEMPTS, LOCKOUT_MS / 1000]
  );
  return rows[0];
}

async function resetFailedAttempts(userId) {
  await query('update users set failed_attempts = 0, locked_until = null where id = $1', [userId]);
}

module.exports = {
  hashPassword, verifyPassword, generateToken, hashToken,
  authenticate, isAdmin, isLockedOut, recordFailedAttempt, resetFailedAttempts,
  MAX_FAILED_ATTEMPTS,
};
