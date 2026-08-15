// One-off migration + seed runner. Usage:
//   DATABASE_URL="postgresql://..." node migrations/seed.js
const fs = require('fs');
const path = require('path');
const { Client } = require('pg');
const bcrypt = require('bcryptjs');

async function main() {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) {
    console.error('Set DATABASE_URL first, e.g.\n  DATABASE_URL="postgresql://..." node migrations/seed.js');
    process.exit(1);
  }

  const client = new Client({ connectionString, ssl: { rejectUnauthorized: false } });
  await client.connect();
  console.log('Connected.');

  const schema = fs.readFileSync(path.join(__dirname, 'schema.sql'), 'utf8');
  await client.query(schema);
  console.log('Schema applied.');

  const { rows: rateRows } = await client.query('select 1 from rates where id = 1');
  if (rateRows.length === 0) {
    await client.query(
      "insert into rates (id, buy, sell, updated_by) values (1, 14850, 15050, 'system')"
    );
    console.log('Seeded default rates.');
  } else {
    console.log('Rates already seeded, skipping.');
  }

  const { rows: ledgerRows } = await client.query('select 1 from ledger_state where id = 1');
  if (ledgerRows.length === 0) {
    await client.query(
      `insert into ledger_state (id, reserves_usd, reserves_syp, day_start_usd, day_start_syp, day_start_started_at, day_start_started_by)
       values (1, 5000, 60000000, 5000, 60000000, now(), 'system')`
    );
    console.log('Seeded default ledger state.');
  } else {
    console.log('Ledger state already seeded, skipping.');
  }

  const { rows: adminRows } = await client.query("select 1 from users where role = 'admin' limit 1");
  if (adminRows.length === 0) {
    const hash = await bcrypt.hash('admin123', 10);
    await client.query(
      "insert into users (username, password_hash, role) values ('admin', $1, 'admin')",
      [hash]
    );
    console.log("Seeded default admin user: username 'admin', password 'admin123' — CHANGE THIS after first login.");
  } else {
    console.log('An admin user already exists, skipping seed admin.');
  }

  await client.end();
  console.log('Done.');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
