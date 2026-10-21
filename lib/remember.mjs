// "Remember me" tokens: unlike the normal in-memory session (cleared every time the app restarts),
// these survive a restart, so signing in once can last until the checkbox is unticked, the browser
// forgets the cookie, a password changes (forgets that one person's devices), or the account is removed.
import fs from 'node:fs';
import path from 'node:path';

const KEPT = 40; // remembered devices across every account; oldest is dropped once there are more than this

export function createRememberStore(appDir) {
  const file = path.join(appDir, 'remember.json');
  let tokens = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : [];
  // Older files just had a bare list of tokens with no owner; without a username there is no safe way
  // to attribute them, so treat that shape as stale and start fresh rather than guessing who they were.
  if (tokens.some((t) => !t.username)) tokens = [];

  function save() {
    fs.writeFileSync(file, JSON.stringify(tokens));
  }

  return {
    /** `{ username } | null` - who this remembered token belongs to, if it's still valid. */
    get: (token) => (token && tokens.find((t) => t.token === token)) || null,
    add(token, username) {
      tokens.push({ token, username, createdAt: new Date().toISOString() });
      if (tokens.length > KEPT) tokens = tokens.slice(tokens.length - KEPT);
      save();
    },
    remove(token) {
      const before = tokens.length;
      tokens = tokens.filter((t) => t.token !== token);
      if (tokens.length !== before) save();
    },
    /** A password change (or account removal) should mean that person's remembered devices sign in again. */
    clearForUser(username) {
      const before = tokens.length;
      tokens = tokens.filter((t) => t.username !== username);
      if (tokens.length !== before) save();
    },
    clearAll() {
      if (!tokens.length) return;
      tokens = [];
      save();
    },
  };
}
