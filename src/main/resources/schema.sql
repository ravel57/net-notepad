create table if not exists notes
(
    id         varchar(32) primary key,
    content    text        not null,
    syntax     varchar(16) not null,
    preview    boolean     not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

alter table notes add column if not exists read_id varchar(32);
alter table notes add column if not exists password_hash varchar(200);
alter table notes add column if not exists expires_at timestamptz;
update notes set read_id = substr(md5(random()::text || id), 1, 12) where read_id is null;
create unique index if not exists notes_read_id_idx on notes (read_id);

create table if not exists note_versions
(
    id         bigserial primary key,
    note_id    varchar(32) not null references notes (id) on delete cascade,
    content    text        not null,
    syntax     varchar(16) not null,
    created_at timestamptz not null default now()
);

create index if not exists note_versions_note_idx on note_versions (note_id, created_at desc);
