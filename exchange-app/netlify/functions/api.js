const http = require('./lib/http');
const { authenticate, isAdmin } = require('./lib/auth');
const { ValidationError } = require('./lib/errors');

const authRoutes = require('./lib/routes/auth');
const ratesRoutes = require('./lib/routes/rates');
const txnRoutes = require('./lib/routes/txns');
const dayCloseRoutes = require('./lib/routes/dayclose');
const adminRoutes = require('./lib/routes/admin');

function getRoute(event) {
  let path = event.path || '/';
  path = path.replace(/^\/\.netlify\/functions\/api/, '');
  path = path.replace(/^\/api/, '');
  if (!path.startsWith('/')) path = '/' + path;
  return path.replace(/\/+$/, '') || '/';
}

const CORS_HEADERS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': 'authorization, content-type',
  'access-control-allow-methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
};

function withCors(response) {
  response.headers = Object.assign({}, response.headers, CORS_HEADERS);
  return response;
}

exports.handler = async (event) => {
  const method = event.httpMethod;
  const route = getRoute(event);

  // Desktop build loads the app from a file:// origin, so cross-origin calls
  // to the live API need CORS — harmless for the web build, which is same-origin.
  if (method === 'OPTIONS') return withCors({ statusCode: 204, headers: {}, body: '' });

  return withCors(await route_(event, method, route));
};

async function route_(event, method, route) {
  const segments = route.split('/').filter(Boolean); // e.g. ['users', ':id', 'role']
  const payload = http.parseBody(event);

  try {
    // ---- public ----
    if (method === 'POST' && route === '/login') {
      const result = await authRoutes.login(payload);
      return http.ok(result);
    }

    // ---- everything else requires a valid session ----
    const user = await authenticate(event);
    if (!user) return http.unauthorized('session_invalidated');

    if (method === 'POST' && route === '/logout') return http.ok(await authRoutes.logout(user));
    if (method === 'GET' && route === '/state') return http.ok(await ratesRoutes.getState());
    if (method === 'POST' && route === '/rates') return http.ok(await ratesRoutes.updateRates(user, payload));
    if (method === 'POST' && route === '/transactions') return http.ok(await txnRoutes.executeTransaction(user, payload));

    // ---- admin-only ----
    if (route.startsWith('/admin') || ['/opening', '/adjustments', '/day-close', '/users', '/factory-reset'].some((p) => route === p || route.startsWith(p + '/'))
      || (segments[0] === 'transactions' && segments.length === 2)) {
      if (!isAdmin(user)) return http.forbidden('Admin access required.');
    }

    if (method === 'GET' && route === '/admin/state') return http.ok(await adminRoutes.getAdminState());
    if (method === 'PUT' && segments[0] === 'transactions' && segments.length === 2) {
      return http.ok(await txnRoutes.editTransaction(user, segments[1], payload));
    }
    if (method === 'DELETE' && segments[0] === 'transactions' && segments.length === 2) {
      return http.ok(await txnRoutes.deleteTransaction(user, segments[1]));
    }
    if (method === 'POST' && route === '/opening') return http.ok(await dayCloseRoutes.setOpening(user, payload));
    if (method === 'POST' && route === '/adjustments') return http.ok(await dayCloseRoutes.adjustReserve(user, payload));
    if (method === 'POST' && route === '/day-close') return http.ok(await dayCloseRoutes.closeDay(user));
    if (method === 'POST' && route === '/users') return http.ok(await adminRoutes.createUser(user, payload));
    if (method === 'PATCH' && segments[0] === 'users' && segments[2] === 'role') {
      return http.ok(await adminRoutes.toggleRole(user, segments[1]));
    }
    if (method === 'DELETE' && segments[0] === 'users' && segments.length === 2) {
      return http.ok(await adminRoutes.deleteUser(user, segments[1]));
    }
    if (method === 'POST' && segments[0] === 'users' && segments[2] === 'password') {
      return http.ok(await adminRoutes.resetPassword(user, segments[1], payload));
    }
    if (method === 'POST' && route === '/factory-reset') return http.ok(await adminRoutes.factoryReset(user, payload));

    return http.notFound('Unknown route.');
  } catch (err) {
    if (err instanceof ValidationError) return http.json(err.statusCode, { error: err.message });
    console.error('API error', method, route, err);
    return http.serverError('Something went wrong. Please try again.');
  }
}
