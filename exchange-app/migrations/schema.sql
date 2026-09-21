create extension if not exists pgcrypto;
create extension if not exists citext;

create table if not exists users (
  id              uuid primary key default gen_random_uuid(),
  username        citext not null unique,
  password_hash   text not null,
  role            text not null check (role in ('admin','staff')),
  created_at      timestamptz not null default now(),
  last_login_at   timestamptz,
  failed_attempts integer not null default 0,
  locked_until    timestamptz
);

create table if not exists sessions (
  user_id      uuid primary key references users(id) on delete cascade,
  token_hash   text not null unique,
  created_at   timestamptz not null default now(),
  last_seen_at timestamptz
);

create table if not exists login_log (
  id       uuid primary key default gen_random_uuid(),
  user_id  uuid references users(id) on delete set null,
  username text not null,
  at       timestamptz not null default now()
);

create table if not exists rates (
  id         smallint primary key default 1 check (id = 1),
  buy        numeric(18,2) not null check (buy > 0),
  sell       numeric(18,2) not null check (sell >= buy),
  updated_at timestamptz not null default now(),
  updated_by text not null
);

create table if not exists rate_history (
  id         uuid primary key default gen_random_uuid(),
  prev_buy   numeric(18,2) not null,
  prev_sell  numeric(18,2) not null,
  buy        numeric(18,2) not null,
  sell       numeric(18,2) not null,
  changed_by text not null,
  changed_at timestamptz not null default now()
);

create table if not exists ledger_state (
  id                    smallint primary key default 1 check (id = 1),
  reserves_usd          numeric(18,2) not null,
  reserves_syp          numeric(20,2) not null,
  day_start_usd         numeric(18,2) not null,
  day_start_syp         numeric(20,2) not null,
  day_start_started_at  timestamptz not null,
  day_start_started_by  text not null
);

create table if not exists transactions (
  id                uuid primary key default gen_random_uuid(),
  type              text not null check (type in ('buy','sell')),
  amount_usd        numeric(18,2) not null check (amount_usd > 0),
  rate_applied      numeric(18,2) not null check (rate_applied > 0),
  buy_rate_at_time  numeric(18,2) not null,
  sell_rate_at_time numeric(18,2) not null,
  amount_syp        numeric(20,2) not null,
  usd_after         numeric(18,2) not null,
  syp_after         numeric(20,2) not null,
  operator          text not null,
  note              text,
  at                timestamptz not null default now(),
  edited_at         timestamptz,
  edited_by         text
);
create index if not exists transactions_at_idx on transactions (at desc);

create table if not exists adjustments (
  id        uuid primary key default gen_random_uuid(),
  currency  text not null check (currency in ('usd','syp')),
  direction text not null check (direction in ('add','remove')),
  amount    numeric(20,2) not null check (amount > 0),
  reason    text,
  "by"      text not null,
  at        timestamptz not null default now()
);
create index if not exists adjustments_at_idx on adjustments (at desc);

create table if not exists day_history (
  id              uuid primary key default gen_random_uuid(),
  closed_at       timestamptz not null default now(),
  closed_by       text not null,
  opened_at       timestamptz not null,
  open_usd        numeric(18,2) not null,
  open_syp        numeric(20,2) not null,
  close_usd       numeric(18,2) not null,
  close_syp       numeric(20,2) not null,
  buy_volume_usd  numeric(18,2) not null,
  sell_volume_usd numeric(18,2) not null,
  txn_count       integer not null,
  profit          numeric(20,2) not null
);

-- Password recovery -------------------------------------------------------
-- Idempotent, so re-running migrations/seed.js upgrades an existing database.
alter table users add column if not exists email citext unique;

create table if not exists password_resets (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references users(id) on delete cascade,
  token_hash text not null unique,
  expires_at timestamptz not null,
  used_at    timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists password_resets_user_idx on password_resets (user_id, created_at desc);

-- Cash variance ------------------------------------------------------------
-- amount_syp always holds the ACTUAL cash that changed hands (it drives the
-- reserves). calculated_syp is what rate x amount said it should have been, and
-- variance_syp is the difference, kept for the "adjusted" flag and day totals.
alter table transactions add column if not exists calculated_syp numeric(20,2);
alter table transactions add column if not exists variance_syp numeric(20,2) not null default 0;
update transactions set calculated_syp = amount_syp where calculated_syp is null;
alter table day_history add column if not exists cash_variance numeric(20,2) not null default 0;
