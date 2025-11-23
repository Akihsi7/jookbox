create table users (
    id uuid primary key,
    display_name varchar(120) not null,
    created_at timestamptz not null default now()
);

create table rooms (
    id uuid primary key,
    code varchar(12) not null unique,
    host_id uuid not null references users (id),
    status varchar(20) not null,
    created_at timestamptz not null default now()
);

create table memberships (
    id uuid primary key,
    room_id uuid not null references rooms (id) on delete cascade,
    user_id uuid not null references users (id) on delete cascade,
    role varchar(20) not null,
    capabilities integer not null,
    joined_at timestamptz not null default now(),
    constraint uq_membership unique (room_id, user_id)
);

create table queue_items (
    id uuid primary key,
    room_id uuid not null references rooms (id) on delete cascade,
    position integer not null,
    video_id varchar(64) not null,
    title varchar(300) not null,
    duration_seconds integer not null,
    thumb_url varchar(500),
    added_by uuid not null references users (id),
    status varchar(20) not null,
    enqueued_at timestamptz not null default now()
);

create index idx_queue_room_position on queue_items(room_id, position);

create table votes (
    id uuid primary key,
    queue_item_id uuid not null references queue_items (id) on delete cascade,
    user_id uuid not null references users (id) on delete cascade,
    type varchar(20) not null,
    created_at timestamptz not null default now(),
    constraint uq_vote_per_user unique (queue_item_id, user_id, type)
);
