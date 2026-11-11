-- Caestro Studio platform on Supabase. Run once in the project's SQL editor (Database → SQL editor → New query).
--
-- works    : scores and instruments people upload from the studio. Uploads wait for review ('pending');
--            an admin approves them ('approved') and only then do they reach the car.
-- likes    : one per person and work; works.likes keeps the count for sorting.
-- profiles : a nickname per account and the admin flag (set by hand, see the end of this file).
-- storage  : bucket 'packs' (public read) holds each work's files under <owner>/<id>/, the format of
--            tools/cloud/README.md. The car reads the list from car_catalog() and the files from the bucket.

-- ------------------------------------------------------------------ profiles

create table if not exists public.profiles (
  id uuid primary key references auth.users on delete cascade,
  nickname text not null default '' check (char_length(nickname) <= 30),
  is_admin boolean not null default false,
  created_at timestamptz not null default now()
);

create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id) values (new.id) on conflict do nothing;
  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((select is_admin from public.profiles where id = auth.uid()), false)
$$;

alter table public.profiles enable row level security;
drop policy if exists profiles_read on public.profiles;
create policy profiles_read on public.profiles for select using (true);
drop policy if exists profiles_update on public.profiles;
-- People change their own nickname; nobody makes themselves an admin.
create policy profiles_update on public.profiles for update
  using (id = auth.uid())
  with check (id = auth.uid() and is_admin = public.is_admin());

-- ------------------------------------------------------------------ works

create table if not exists public.works (
  id text primary key check (id ~ '^[a-z0-9][a-z0-9_-]{0,39}$'),
  owner uuid not null default auth.uid() references auth.users on delete cascade,
  kind text not null check (kind in ('score', 'instrument')),
  title text not null check (char_length(title) between 1 and 60),
  subtitle text not null default '' check (char_length(subtitle) <= 120),
  creator text not null default '' check (char_length(creator) <= 30),
  license text not null default '' check (char_length(license) <= 60),
  color text not null default '#9FB4C2' check (color ~ '^#[0-9A-Fa-f]{6}$'),
  look text not null default '',
  stages int[] not null default '{}',
  manifest jsonb not null,
  files text[] not null,
  bytes int not null default 0,
  forked_from text references public.works (id) on delete set null,
  status text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
  review_note text not null default '' check (char_length(review_note) <= 200),
  likes int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists works_status_likes on public.works (status, likes desc, updated_at desc);
create index if not exists works_owner on public.works (owner);

-- Owners may change their work, but any change sends it back to review; status, likes and the review
-- note are the admin's (and the like counter's) alone.
create or replace function public.works_guard() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if current_setting('caestro.like_count', true) = 'on' then
    return new;
  end if;
  if tg_op = 'INSERT' then
    if not public.is_admin() then
      new.owner := auth.uid();
      new.status := 'pending';
      new.review_note := '';
    end if;
    new.likes := 0;
  else
    new.owner := old.owner;
    new.likes := old.likes;
    if not public.is_admin() then
      new.status := 'pending';
      new.review_note := '';
    end if;
  end if;
  new.updated_at := now();
  return new;
end $$;

drop trigger if exists works_guard on public.works;
create trigger works_guard before insert or update on public.works
  for each row execute function public.works_guard();

alter table public.works enable row level security;
drop policy if exists works_read on public.works;
create policy works_read on public.works for select
  using (status = 'approved' or owner = auth.uid() or public.is_admin());
drop policy if exists works_insert on public.works;
create policy works_insert on public.works for insert
  with check (auth.uid() is not null and (owner = auth.uid() or public.is_admin()));
drop policy if exists works_update on public.works;
create policy works_update on public.works for update
  using (owner = auth.uid() or public.is_admin());
drop policy if exists works_delete on public.works;
create policy works_delete on public.works for delete
  using (owner = auth.uid() or public.is_admin());

-- ------------------------------------------------------------------ likes

create table if not exists public.likes (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  work_id text not null references public.works (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, work_id)
);

alter table public.likes enable row level security;
drop policy if exists likes_read on public.likes;
create policy likes_read on public.likes for select using (true);
drop policy if exists likes_insert on public.likes;
create policy likes_insert on public.likes for insert
  with check (user_id = auth.uid() and exists (select 1 from public.works w where w.id = work_id and w.status = 'approved'));
drop policy if exists likes_delete on public.likes;
create policy likes_delete on public.likes for delete using (user_id = auth.uid());

create or replace function public.likes_count() returns trigger
language plpgsql security definer set search_path = public as $$
declare target text := coalesce(new.work_id, old.work_id);
begin
  perform set_config('caestro.like_count', 'on', true);
  update public.works set likes = (select count(*) from public.likes where work_id = target) where id = target;
  perform set_config('caestro.like_count', 'off', true);
  return null;
end $$;

drop trigger if exists likes_count on public.likes;
create trigger likes_count after insert or delete on public.likes
  for each row execute function public.likes_count();

-- ------------------------------------------------------------------ what the car downloads

-- The car's list in the format of docs/cloud/catalog.json: approved works, most liked first. File paths are
-- relative to the bucket's public address (<project>/storage/v1/object/public/packs/).
create or replace function public.car_catalog() returns json
language sql stable security definer set search_path = public as $$
  select json_build_object(
    'format', 1,
    'name', '카에스트로 클라우드',
    'items', coalesce((
      select json_agg(json_build_object(
        'id', w.id, 'type', w.kind, 'title', w.title, 'subtitle', w.subtitle, 'creator', w.creator,
        'license', w.license, 'look', w.look, 'color', w.color, 'stages', w.stages, 'files', w.files,
        'bytes', w.bytes, 'likes', w.likes) order by w.likes desc, w.updated_at desc)
      from public.works w where w.status = 'approved'), '[]'::json))
$$;
grant execute on function public.car_catalog() to anon, authenticated;

-- ------------------------------------------------------------------ files

insert into storage.buckets (id, name, public, file_size_limit)
values ('packs', 'packs', true, 16777216)
on conflict (id) do update set public = true, file_size_limit = 16777216;

-- Anyone may read (the car has no account); people write only under their own folder; admins anywhere.
drop policy if exists packs_read on storage.objects;
create policy packs_read on storage.objects for select using (bucket_id = 'packs');
drop policy if exists packs_write on storage.objects;
create policy packs_write on storage.objects for insert to authenticated
  with check (bucket_id = 'packs' and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()));
drop policy if exists packs_update on storage.objects;
create policy packs_update on storage.objects for update to authenticated
  using (bucket_id = 'packs' and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()));
drop policy if exists packs_delete on storage.objects;
create policy packs_delete on storage.objects for delete to authenticated
  using (bucket_id = 'packs' and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()));

-- ------------------------------------------------------------------ the admin
-- After signing in to the studio once, make your account the admin (replace the e-mail):
--   update public.profiles set is_admin = true
--   where id = (select id from auth.users where email = 'you@example.com');
