#!/usr/bin/env bash
set -euo pipefail

RELEASE_PATH="${1:?release path is required}"
BACKUP_DIR="${2:-/var/backups/yubai-blog}"
DB_NAME="${3:-yubai_blog}"
MIN_FREE_KIB="${MIN_FREE_KIB:-1048576}"
MAX_BACKUP_AGE_SECONDS="${MAX_BACKUP_AGE_SECONDS:-93600}"
CONTRACT="${RELEASE_PATH}/deploy/release-compatibility.env"

[[ -f "${CONTRACT}" ]] || { echo "Release compatibility contract is missing" >&2; exit 1; }
# shellcheck disable=SC1090 -- contract is a reviewed, immutable release artifact.
source "${CONTRACT}"

[[ "${SCHEMA_TARGET:-}" =~ ^[0-9]+$ ]] || { echo "Invalid SCHEMA_TARGET" >&2; exit 1; }
[[ "${ROLLBACK_APP_MIN_SCHEMA:-}" =~ ^[0-9]+$ ]] || { echo "Invalid rollback schema floor" >&2; exit 1; }
[[ "${MIGRATION_MODE:-}" == "expand-only" ]] || { echo "Release is not rollback-compatible" >&2; exit 1; }
[[ -s "${RELEASE_PATH}/backend/blog-backend.jar" ]] || { echo "Backend artifact is missing" >&2; exit 1; }
[[ -s "${RELEASE_PATH}/frontend/client/index.html" ]] || { echo "Frontend artifact is missing" >&2; exit 1; }

latest_manifest="$(find "${BACKUP_DIR}" -maxdepth 1 -type f -name 'SHA256SUMS-*' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)"
[[ -n "${latest_manifest}" && -s "${latest_manifest}" ]] || { echo "No completed backup manifest found" >&2; exit 1; }
backup_age="$(( $(date +%s) - $(stat -c %Y "${latest_manifest}") ))"
(( backup_age <= MAX_BACKUP_AGE_SECONDS )) || { echo "Latest backup is older than 26 hours" >&2; exit 1; }

(
  cd "${BACKUP_DIR}"
  sha256sum --check "${latest_manifest##*/}"
)
stamp="${latest_manifest##*SHA256SUMS-}"
dump="${BACKUP_DIR}/yubai_blog-${stamp}.dump"
storage_archive="${BACKUP_DIR}/attachments-${stamp}.tar.gz"
inventory="${BACKUP_DIR}/storage-inventory-${stamp}.sha256"
[[ -s "${dump}" && -s "${storage_archive}" && -s "${inventory}" ]] || {
  echo "Backup batch is incomplete" >&2
  exit 1
}
pg_restore --list "${dump}" >/dev/null
tar --list --gzip --file="${storage_archive}" >/dev/null

available_kib="$(df -Pk "${RELEASE_PATH}" | awk 'NR==2 {print $4}')"
[[ "${available_kib}" =~ ^[0-9]+$ ]] || { echo "Unable to read release disk capacity" >&2; exit 1; }
(( available_kib >= MIN_FREE_KIB )) || { echo "Insufficient release disk capacity" >&2; exit 1; }

APP_ENV="${APP_ENV:-/etc/yubai-blog/app.env}"
[[ -s "${APP_ENV}" ]] || { echo "Application database environment is missing" >&2; exit 1; }
set -a
# shellcheck disable=SC1090 -- root-owned application environment is the production source of truth.
source "${APP_ENV}"
set +a
java -Dloader.main=com.yubai.blog.common.FlywayReleasePreflight \
  -cp "${RELEASE_PATH}/backend/blog-backend.jar" \
  org.springframework.boot.loader.launch.PropertiesLauncher "${SCHEMA_TARGET}"

current_schema="$(sudo -u postgres psql -d "${DB_NAME}" -Atc \
  "select coalesce((select version from flyway_schema_history where success order by installed_rank desc limit 1), '0')")"
failed_migrations="$(sudo -u postgres psql -d "${DB_NAME}" -Atc \
  "select count(*) from flyway_schema_history where success = false")"
[[ "${current_schema}" =~ ^[0-9]+$ && "${failed_migrations}" == "0" ]] || {
  echo "Flyway history is invalid" >&2
  exit 1
}
(( current_schema <= SCHEMA_TARGET )) || { echo "Database is newer than this release" >&2; exit 1; }
(( current_schema >= ROLLBACK_APP_MIN_SCHEMA )) || {
  echo "Current schema is outside the reviewed code rollback window" >&2
  exit 1
}
(( ROLLBACK_APP_MIN_SCHEMA <= SCHEMA_TARGET )) || { echo "Invalid rollback compatibility window" >&2; exit 1; }

echo "Release preflight passed: backup=${stamp} schema=V${current_schema}->V${SCHEMA_TARGET} free_kib=${available_kib}"
