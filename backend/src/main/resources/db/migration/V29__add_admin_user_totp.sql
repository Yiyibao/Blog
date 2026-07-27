alter table admin_users add column totp_secret_encrypted varchar(255);
alter table admin_users add column totp_enabled boolean not null default false;
alter table admin_users add constraint admin_users_totp_secret_required
    check (not totp_enabled or totp_secret_encrypted is not null);
