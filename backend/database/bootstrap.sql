-- Run this once as the PostgreSQL superuser before starting the backend.
\set ON_ERROR_STOP on
\prompt 'Password for the new yubai_app database user: ' yubai_password
create user yubai_app with password :'yubai_password';
create database yubai_blog owner yubai_app encoding 'UTF8';
