alter table note_attachments add column storage_key varchar(512);
alter table note_attachments alter column content drop not null;
alter table note_attachments add constraint uq_note_attachments_storage_key unique (storage_key);
alter table note_attachments add constraint ck_note_attachments_content_source
    check (content is not null or storage_key is not null);
