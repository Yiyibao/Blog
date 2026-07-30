#!/usr/bin/env bash
set -euo pipefail

APP_USER="yubai"
APP_DIR="/opt/yubai-blog"
CONFIG_DIR="/etc/yubai-blog"
ENV_FILE="${CONFIG_DIR}/app.env"
CREDENTIALS_FILE="/home/ubuntu/yubai-blog-credentials.txt"
DB_NAME="yubai_blog"
DB_USER="yubai_app"

OPENCODE_BIN="/usr/bin/opencode"
OPENCODE_USER="opencode"
OPENCODE_HOME="/var/lib/opencode"
OPENCODE_CONFIG_DIR="/etc/yubai-blog-opencode"
OPENCODE_ENV_FILE="${OPENCODE_CONFIG_DIR}/opencode.env"
OPENCODE_SERVICE="yubai-blog-opencode.service"
YT_DLP_BIN="/usr/local/bin/yt-dlp"
YT_DLP_PACKAGE_DIR="/opt/yt-dlp/2026.7.4"
SERVICE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -x "${OPENCODE_BIN}" ]]; then
    echo "Prerequisite not met: ${OPENCODE_BIN} is missing or not executable" >&2
    exit 1
fi

if [[ ! -f "${YT_DLP_PACKAGE_DIR}/yt_dlp/__main__.py" ]]; then
    echo "Prerequisite not met: yt-dlp package is missing from ${YT_DLP_PACKAGE_DIR}" >&2
    exit 1
fi

for source_file in opencode.json yt-dlp yubai-blog-opencode.service yubai-blog.service; do
    if [[ ! -f "${SERVICE_DIR}/${source_file}" ]]; then
        echo "Deployment source is missing: ${SERVICE_DIR}/${source_file}" >&2
        exit 1
    fi
done

install -o root -g root -m 0755 "${SERVICE_DIR}/yt-dlp" "${YT_DLP_BIN}"

if [[ -e "${ENV_FILE}" ]]; then
    echo "Refusing to replace existing production configuration: ${ENV_FILE}" >&2
    exit 1
fi

if [[ -e "${OPENCODE_ENV_FILE}" ]]; then
    echo "Refusing to replace existing sidecar configuration: ${OPENCODE_ENV_FILE}" >&2
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

if ! id "${OPENCODE_USER}" >/dev/null 2>&1; then
    useradd --system --home-dir "${OPENCODE_HOME}" --shell /usr/sbin/nologin "${OPENCODE_USER}"
fi
install -d -o "${OPENCODE_USER}" -g "${OPENCODE_USER}" -m 0750 "${OPENCODE_HOME}/workspace"
install -d -o root -g "${OPENCODE_USER}" -m 0750 "${OPENCODE_CONFIG_DIR}"

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

OPENCODE_PASSWORD="$(openssl rand -hex 18)"

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
    printf 'APP_AI_OPENCODE_USERNAME=opencode\n'
    printf 'APP_AI_OPENCODE_PASSWORD=%s\n' "${OPENCODE_PASSWORD}"
    printf 'APP_AI_OPENCODE_AGENT=blog-ai\n'
    printf 'APP_AI_OPENCODE_PROVIDER_ID=opencode-go\n'
    printf 'APP_RECIPE_VIDEO_ENABLED=true\n'
    printf 'APP_RECIPE_YT_DLP_PATH=%s\n' "${YT_DLP_BIN}"
} > "${ENV_FILE}"

chown root:"${APP_USER}" "${ENV_FILE}"
chmod 0640 "${ENV_FILE}"

{
    printf 'OPENCODE_SERVER_USERNAME=opencode\n'
    printf 'OPENCODE_SERVER_PASSWORD=%s\n' "${OPENCODE_PASSWORD}"
    printf 'OPENCODE_CONFIG=%s/opencode.json\n' "${OPENCODE_CONFIG_DIR}"
} > "${OPENCODE_ENV_FILE}"
chown root:"${OPENCODE_USER}" "${OPENCODE_ENV_FILE}"
chmod 0640 "${OPENCODE_ENV_FILE}"

{
    printf 'Blog admin URL: https://hxnf.top/admin/login\n'
    printf 'Username: admin\n'
    printf 'Password: %s\n' "${ADMIN_PASSWORD}"
} > "${CREDENTIALS_FILE}"
chown ubuntu:ubuntu "${CREDENTIALS_FILE}"
chmod 0600 "${CREDENTIALS_FILE}"

install -m 0640 "${SERVICE_DIR}/opencode.json" "${OPENCODE_CONFIG_DIR}/opencode.json"
chown root:"${OPENCODE_USER}" "${OPENCODE_CONFIG_DIR}/opencode.json"
install -m 0644 "${SERVICE_DIR}/yubai-blog-opencode.service" /etc/systemd/system/
install -m 0644 "${SERVICE_DIR}/yubai-blog.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable "${OPENCODE_SERVICE}"

echo "Production database and secrets created successfully."
echo "Download ${CREDENTIALS_FILE} and store it securely."
