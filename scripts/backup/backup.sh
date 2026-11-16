#!/usr/bin/env bash
#
# AnimeQuiz backup and restore tool.
#
# This script runs inside the Compose service "backup" (image postgres:18.6).
# It is not meant to be executed directly on the host.
#
# Usage:
#   backup.sh db [--keep-days N]
#   backup.sh storage [--keep-archives N]
#   backup.sh all
#   backup.sh list
#   backup.sh restore-db [--file PATH] [--target DB] [--force]
#   backup.sh restore-storage [--file PATH] [--force] [--remove-old]
#
set -euo pipefail

BACKUP_ROOT="${BACKUP_ROOT:-/backups}"
DB_DIR="$BACKUP_ROOT/db"
STORAGE_DIR="$BACKUP_ROOT/storage"
LOG_DIR="$BACKUP_ROOT/logs"
STORAGE_ROOT="${STORAGE_ROOT:-/storage}"

PGHOST="${PGHOST:-db}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-AnimeQuiz}"
PGUSER="${PGUSER:-animequiz}"

KEEP_DAYS="${KEEP_DAYS:-30}"
KEEP_ARCHIVES="${KEEP_ARCHIVES:-4}"

APP_HOST="${APP_HOST:-app}"
APP_PORT="${APP_PORT:-8081}"

mkdir -p "$DB_DIR" "$STORAGE_DIR" "$LOG_DIR"
LOG_FILE="$LOG_DIR/backup-$(date +%Y-%m).log"

log() {
    local level="$1"
    shift
    local line
    line="$(date '+%Y-%m-%d %H:%M:%S') [$level] $*"
    printf '%s\n' "$line"
    printf '%s\n' "$line" >> "$LOG_FILE"
}

fail() {
    log ERROR "$*"
    exit 1
}

usage() {
    cat <<'EOF'
Usage:
  backup.sh db [--keep-days N]
  backup.sh storage [--keep-archives N]
  backup.sh all
  backup.sh list
  backup.sh restore-db [--file PATH] [--target DB] [--force]
  backup.sh restore-storage [--file PATH] [--force] [--remove-old]

Paths passed to --file are relative to /backups or absolute.
EOF
}

app_is_reachable() {
    (exec 3<>"/dev/tcp/${APP_HOST}/${APP_PORT}") 2>/dev/null
}

newest_file() {
    local dir="$1"
    local pattern="$2"
    find "$dir" -maxdepth 1 -type f -name "$pattern" -printf '%T@ %p\n' \
        | sort -nr \
        | head -n 1 \
        | cut -d' ' -f2-
}

resolve_backup_path() {
    local path="$1"
    case "$path" in
        /*) printf '%s\n' "$path" ;;
        *)  printf '%s\n' "$BACKUP_ROOT/$path" ;;
    esac
}

backup_db() {
    local keep_days="$KEEP_DAYS"

    while [ $# -gt 0 ]; do
        case "$1" in
            --keep-days) keep_days="$2"; shift 2 ;;
            *) fail "Unknown option for db: $1" ;;
        esac
    done

    local stamp dump partial size
    stamp="$(date +%Y-%m-%d_%H%M%S)"
    dump="$DB_DIR/${PGDATABASE}_${stamp}.dump"
    partial="$dump.part"

    log INFO "Database backup: ${PGUSER}@${PGHOST}:${PGPORT}/${PGDATABASE}"
    pg_dump \
        --format=custom \
        --compress=9 \
        --no-owner \
        --no-privileges \
        --lock-wait-timeout=60s \
        --encoding=UTF8 \
        --file="$partial"

    [ -s "$partial" ] || fail "pg_dump did not create a dump file."

    size="$(stat -c '%s' "$partial")"
    [ "$size" -ge 1024 ] || fail "Dump is suspiciously small: $size bytes."

    pg_restore --list "$partial" >/dev/null
    log INFO "Dump verified with pg_restore --list."

    mv "$partial" "$dump"
    (cd "$DB_DIR" && sha256sum "$(basename "$dump")" > "$(basename "$dump").sha256")

    log INFO "BACKUP OK: $dump ($(awk -v s="$size" 'BEGIN { printf "%.1f", s / 1048576 }') MB)"
    log INFO "SHA256: $(cut -d' ' -f1 "$dump.sha256")"

    find "$DB_DIR" -maxdepth 1 -type f -name '*.dump' -mtime +"$keep_days" -print0 \
        | while IFS= read -r -d '' old; do
            rm -f "$old" "$old.sha256"
            log INFO "Deleted old dump: $(basename "$old")"
        done

    log INFO "Stored database backups: $(find "$DB_DIR" -maxdepth 1 -type f -name '*.dump' | wc -l) (retention ${keep_days} days)"
}

backup_storage() {
    local keep_archives="$KEEP_ARCHIVES"

    while [ $# -gt 0 ]; do
        case "$1" in
            --keep-archives) keep_archives="$2"; shift 2 ;;
            *) fail "Unknown option for storage: $1" ;;
        esac
    done

    [ -d "$STORAGE_ROOT" ] || fail "Storage directory not found: $STORAGE_ROOT"

    local stamp archive partial source_count archived
    stamp="$(date +%Y-%m-%d_%H%M%S)"
    archive="$STORAGE_DIR/storage_${stamp}.tar.gz"
    partial="$archive.part"

    source_count="$(find "$STORAGE_ROOT" \
        \( -path "$STORAGE_ROOT/logs" -o -path "$STORAGE_ROOT/.restore-*" \) -prune \
        -o -type f -print | wc -l)"
    [ "$source_count" -gt 0 ] || fail "Storage directory is empty: $STORAGE_ROOT"

    log INFO "Storage backup: $source_count files from $STORAGE_ROOT"

    tar -czf "$partial" -C "$STORAGE_ROOT" \
        --exclude=logs \
        --exclude='.restore-*' \
        .

    archived="$(tar -tzf "$partial" | grep -vc '/$' || true)"
    [ "$archived" -ge "$source_count" ] || fail "Archive content mismatch: source has $source_count files, archive has $archived."
    log INFO "Archive verified: $archived files."

    mv "$partial" "$archive"
    log INFO "BACKUP OK: $archive ($(awk -v s="$(stat -c '%s' "$archive")" 'BEGIN { printf "%.1f", s / 1048576 }') MB)"

    if [ "$keep_archives" -lt 1 ]; then
        keep_archives=1
    fi

    find "$STORAGE_DIR" -maxdepth 1 -type f -name 'storage_*.tar.gz' -printf '%T@ %p\n' \
        | sort -nr \
        | tail -n +"$((keep_archives + 1))" \
        | cut -d' ' -f2- \
        | while IFS= read -r old; do
            rm -f "$old"
            log INFO "Deleted old archive: $(basename "$old")"
        done

    log INFO "Stored storage archives: $(find "$STORAGE_DIR" -maxdepth 1 -type f -name 'storage_*.tar.gz' | wc -l) (keep ${keep_archives})"
}

restore_db() {
    local file="" target="AnimeQuiz_restore" force=0

    while [ $# -gt 0 ]; do
        case "$1" in
            --file) file="$2"; shift 2 ;;
            --target) target="$2"; shift 2 ;;
            --force) force=1; shift ;;
            *) fail "Unknown option for restore-db: $1" ;;
        esac
    done

    [[ "$target" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail "Invalid target database name: $target"

    if [ -z "$file" ]; then
        file="$(newest_file "$DB_DIR" '*.dump')"
    else
        file="$(resolve_backup_path "$file")"
    fi

    [ -n "$file" ] && [ -f "$file" ] || fail "No dump file found. Pass --file explicitly."

    log INFO "Dump: $file ($(awk -v s="$(stat -c '%s' "$file")" 'BEGIN { printf "%.1f", s / 1048576 }') MB)"
    log INFO "Target: ${PGUSER}@${PGHOST}:${PGPORT}/${target}"

    pg_restore --list "$file" >/dev/null || fail "Dump is not readable by pg_restore."

    if [ -f "$file.sha256" ]; then
        local expected actual
        expected="$(cut -d' ' -f1 "$file.sha256")"
        actual="$(sha256sum "$file" | cut -d' ' -f1)"
        [ "$expected" = "$actual" ] || fail "SHA256 mismatch. Expected $expected, got $actual."
        log INFO "SHA256 checksum verified."
    fi

    if [ "$target" = "$PGDATABASE" ] && [ "$force" -ne 1 ]; then
        fail "Refusing to overwrite the live database '$target'. Stop the application and pass --force."
    fi

    local active
    active="$(psql -d postgres -tAc "SELECT count(*) FROM pg_stat_activity WHERE datname = '$target' AND pid <> pg_backend_pid();")"
    if [ "${active:-0}" -gt 0 ] && [ "$force" -ne 1 ]; then
        fail "Target database has ${active} active connection(s). Stop the application and pass --force."
    fi

    dropdb --if-exists --force "$target"
    createdb \
        --encoding=UTF8 \
        --template=template0 \
        --locale=C.UTF-8 \
        --owner="$PGUSER" \
        "$target"

    log INFO "Restoring..."
    pg_restore \
        --no-owner \
        --no-privileges \
        --exit-on-error \
        --single-transaction \
        -d "$target" \
        "$file"

    log INFO "Row counts:"
    psql -d "$target" -c "
        SELECT 'users' AS table_name, count(*) FROM main.users
        UNION ALL SELECT 'authorities', count(*) FROM main.authorities
        UNION ALL SELECT 'anime', count(*) FROM main.anime
        UNION ALL SELECT 'user_anime', count(*) FROM main.user_anime
        UNION ALL SELECT 'user_import', count(*) FROM main.user_import
        UNION ALL SELECT 'import_job', count(*) FROM main.import_job;
    " | while IFS= read -r line; do log INFO "$line"; done

    log INFO "Flyway history:"
    psql -d "$target" -c "SELECT version, description, success FROM main.flyway_schema_history ORDER BY installed_rank;" \
        | while IFS= read -r line; do log INFO "$line"; done

    if [ "$target" = "$PGDATABASE" ]; then
        log WARN "Live database '$target' was replaced. Start the application and verify it manually."
    else
        log INFO "Test database '$target' is ready."
    fi
}

restore_storage() {
    local file="" force=0 remove_old=0

    while [ $# -gt 0 ]; do
        case "$1" in
            --file) file="$2"; shift 2 ;;
            --force) force=1; shift ;;
            --remove-old) remove_old=1; shift ;;
            *) fail "Unknown option for restore-storage: $1" ;;
        esac
    done

    if [ -z "$file" ]; then
        file="$(newest_file "$STORAGE_DIR" 'storage_*.tar.gz')"
    else
        file="$(resolve_backup_path "$file")"
    fi

    [ -n "$file" ] && [ -f "$file" ] || fail "No storage archive found. Pass --file explicitly."

    log INFO "Archive: $file ($(awk -v s="$(stat -c '%s' "$file")" 'BEGIN { printf "%.1f", s / 1048576 }') MB)"
    log INFO "Target: $STORAGE_ROOT"

    if app_is_reachable; then
        if [ "$force" -ne 1 ]; then
            fail "The application is reachable on ${APP_HOST}:${APP_PORT}. Stop it first, or pass --force."
        fi
        log WARN "The application is reachable on ${APP_HOST}:${APP_PORT}. Restoring anyway because --force was set."
    fi

    tar -tzf "$file" >/dev/null || fail "Archive is not readable by tar."

    [ -d "$STORAGE_ROOT" ] || fail "Storage directory not found: $STORAGE_ROOT"

    local old app_owner
    old="$STORAGE_ROOT/.restore-$(date +%Y%m%d-%H%M%S)"
    app_owner="$(stat -c '%u:%g' "$STORAGE_ROOT/posters" 2>/dev/null || true)"
    mkdir -p "$old"

    local moved=()
    local entry name
    shopt -s nullglob dotglob
    for entry in "$STORAGE_ROOT"/*; do
        name="$(basename "$entry")"
        [ "$name" = "logs" ] && continue
        case "$name" in
            .restore-*) continue ;;
        esac
        mv "$entry" "$old/"
        moved+=("$name")
    done
    shopt -u nullglob dotglob

    log INFO "Moved current storage to $old"

    if ! tar -xzpf "$file" -C "$STORAGE_ROOT"; then
        log ERROR "Extraction failed. Rolling back."
        shopt -s nullglob dotglob
        for entry in "$STORAGE_ROOT"/*; do
            name="$(basename "$entry")"
            [ "$name" = "logs" ] && continue
            case "$name" in
                .restore-*) continue ;;
            esac
            rm -rf "$entry"
        done
        shopt -u nullglob dotglob
        for name in "${moved[@]}"; do
            mv "$old/$name" "$STORAGE_ROOT/"
        done
        rmdir "$old" 2>/dev/null || true
        fail "Storage restore failed. Previous files were put back."
    fi

    log INFO "Extracted files: $(find "$STORAGE_ROOT" \
        \( -path "$STORAGE_ROOT/logs" -o -path "$STORAGE_ROOT/.restore-*" \) -prune \
        -o -type f -print | wc -l)"

    if [ -n "$app_owner" ]; then
        chown -R "$app_owner" "$STORAGE_ROOT"
        log INFO "Storage ownership set to $app_owner."
    else
        log WARN "Owner of $STORAGE_ROOT/posters is unknown. Ownership left unchanged."
    fi

    if [ "$remove_old" -eq 1 ]; then
        rm -rf "$old"
        log INFO "Previous storage removed."
    else
        log INFO "Previous storage kept at $old. Delete it after verification."
    fi

    log INFO "Storage restore finished. Start the application and check posters and avatars."
}

list_backups() {
    log INFO "Database dumps:"
    find "$DB_DIR" -maxdepth 1 -type f -name '*.dump' -printf '%TY-%Tm-%Td %TH:%TM %10s %f\n' | sort -r
    log INFO "Storage archives:"
    find "$STORAGE_DIR" -maxdepth 1 -type f -name 'storage_*.tar.gz' -printf '%TY-%Tm-%Td %TH:%TM %10s %f\n' | sort -r
}

command="${1:-}"
[ -n "$command" ] || { usage; exit 1; }
shift || true

case "$command" in
    db)              backup_db "$@" ;;
    storage)         backup_storage "$@" ;;
    all)             backup_db && backup_storage ;;
    list)            list_backups ;;
    restore-db)     restore_db "$@" ;;
    restore-storage) restore_storage "$@" ;;
    -h|--help|help)  usage ;;
    *)               usage; exit 1 ;;
esac
