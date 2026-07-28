#!/usr/bin/env bash
set -euo pipefail

APP_USER="yubai"
APP_DIR="/opt/yubai-blog"
CONFIG_DIR="/etc/yubai-blog"
ENV_FILE="${CONFIG_DIR}/app.env"
CREDENTIALS_FILE="/home/ubuntu/yubai-blog-credentials.txt"
DB_NAME="yubai_blog"
DB_USER="yubai_app"

if [[ -e "${ENV_FILE}" ]]; then
    echo "Refusing to replace existing production configuration: ${ENV_FILE}" >&2
    exit 1
fi

if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}'" | grep -q 1; then
    echo "Database role ${DB_USER} already exists but ${ENV_FILE} is missing." >&2
    exit 1
fi

if sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1; then
    echo "Database ${DB_NAME} already exists but ${ENV_FILE} is missing." >&2
    exit 1
fi

DB_PASSWORD="$(openssl rand -hex 24)"
JWT_SECRET="$(openssl rand -hex 32)"
ADMIN_PASSWORD="$(openssl rand -hex 18)"

if ! id "${APP_USER}" >/dev/null 2>&1; then
    useradd --system --home-dir "${APP_DIR}" --shell /usr/sbin/nologin "${APP_USER}"
fi

# Minimum safe bootstrap: o+x on APP_DIR lets nginx traverse; o+rx on releases/
# lets nginx serve frontend files. shared/ and shared/attachments remain non-public.
install -d -o "${APP_USER}" -g "${APP_USER}" -m 0751 "${APP_DIR}"
install -d -o "${APP_USER}" -g "${APP_USER}" -m 0755 "${APP_DIR}/releases"
install -d -o "${APP_USER}" -g "${APP_USER}" -m 0750 "${APP_DIR}/shared"
install -d -o "${APP_USER}" -g "${APP_USER}" -m 0750 "${APP_DIR}/shared/attachments"
install -d -o root -g "${APP_USER}" -m 0750 "${CONFIG_DIR}"

sudo -u postgres psql -v ON_ERROR_STOP=1 \
    -c "CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASSWORD}'"
sudo -u postgres createdb --owner="${DB_USER}" --encoding=UTF8 "${DB_NAME}"

umask 0077
{
    printf 'DB_URL=jdbc:postgresql://127.0.0.1:5432/%s\n' "${DB_NAME}"
    printf 'DB_USERNAME=%s\n' "${DB_USER}"
    printf 'DB_PASSWORD=%s\n' "${DB_PASSWORD}"
    printf 'SERVER_PORT=8080\n'
    printf 'SERVER_ADDRESS=127.0.0.1\n'
    printf 'APP_CORS_ALLOWED_ORIGINS=https://hxnf.top,https://www.hxnf.top\n'
    printf 'APP_JWT_SECRET=%s\n' "${JWT_SECRET}"
    printf 'APP_JWT_TTL=PT2H\n'
    printf 'APP_ATTACHMENT_STORAGE_DIR=%s\n' "${APP_DIR}/shared/attachments"
    printf 'APP_ADMIN_USERNAME=admin\n'
    printf 'APP_ADMIN_PASSWORD=%s\n' "${ADMIN_PASSWORD}"
} > "${ENV_FILE}"
chown root:"${APP_USER}" "${ENV_FILE}"
chmod 0640 "${ENV_FILE}"

{
    printf 'Blog admin URL: https://hxnf.top/admin/login\n'
    printf 'Username: admin\n'
    printf 'Password: %s\n' "${ADMIN_PASSWORD}"
} > "${CREDENTIALS_FILE}"
chown ubuntu:ubuntu "${CREDENTIALS_FILE}"
chmod 0600 "${CREDENTIALS_FILE}"

echo "Production database and secrets created successfully."
echo "Download ${CREDENTIALS_FILE} and store it securely."
