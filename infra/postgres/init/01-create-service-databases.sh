#!/bin/bash
# Runs once, on first initialization of an empty Postgres data volume.
# One database and one login role per service (data ownership boundary, least privilege).
set -euo pipefail

create_db() {
  local db="$1" user="$2" password="$3"
  if [ -z "$password" ]; then
    echo "Missing password for $user" >&2
    exit 1
  fi
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v db="$db" -v usr="$user" -v pwd="$password" <<'SQL'
CREATE ROLE :"usr" LOGIN PASSWORD :'pwd';
CREATE DATABASE :"db" OWNER :"usr";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
SQL
}

create_db auth_db         auth_user         "${AUTH_DB_PASSWORD:-}"
create_db hotel_db        hotel_user        "${HOTEL_DB_PASSWORD:-}"
create_db booking_db      booking_user      "${BOOKING_DB_PASSWORD:-}"
create_db payment_db      payment_user      "${PAYMENT_DB_PASSWORD:-}"
create_db review_db       review_user       "${REVIEW_DB_PASSWORD:-}"
create_db notification_db notification_user "${NOTIFICATION_DB_PASSWORD:-}"
