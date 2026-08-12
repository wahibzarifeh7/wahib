function json(statusCode, body) {
  return {
    statusCode,
    headers: { 'content-type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  };
}

function ok(body) { return json(200, body); }
function badRequest(error) { return json(400, { error }); }
function unauthorized(error) { return json(401, { error: error || 'unauthorized' }); }
function forbidden(error) { return json(403, { error: error || 'forbidden' }); }
function notFound(error) { return json(404, { error: error || 'not_found' }); }
function serverError(error) { return json(500, { error: error || 'server_error' }); }

function parseBody(event) {
  if (!event.body) return {};
  try { return JSON.parse(event.body); } catch (e) { return {}; }
}

module.exports = { json, ok, badRequest, unauthorized, forbidden, notFound, serverError, parseBody };
