alter table note_attachments add column if not exists deleted_at timestamptz;
create index if not exists note_attachments_deleted_at_idx
    on note_attachments (deleted_at) where deleted_at is not null;
