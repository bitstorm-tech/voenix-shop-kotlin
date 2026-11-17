#!/usr/bin/env bash
# Copies the catalog and the operations data of a database the backend has
# migrated into another one with the same migrations applied (schema `voenix`),
# for example from production into a local development database.
#
# Copied (COPY_TABLES): articles, prompts, categories, prices, VAT, suppliers,
# countries, promotions, and production destinations, ids and sequence values
# included. The target's rows in these tables are replaced.
#
# Not copied, and emptied in the target (WIPE_TABLES): carts, orders, payments,
# production jobs, and everything else that belongs to a customer transaction.
# They would point to the target's replaced articles and prompts.
#
# Not copied, and kept in the target (KEEP_TABLES): user accounts and
# schema_migrations. A supplier login keeps its supplier link only when that
# supplier id exists in the copied data.
#
# Stored files (images, PDFs) are NOT copied. Everything in the target happens
# in one transaction: if anything fails, the target stays as it was.
#
# See docs/dev/backend/getting-started/copying-a-database.md.
set -euo pipefail

COPY_TABLES=(
  countries value_added_taxes suppliers prices promotions
  article_types article_categories article_category_ordering article_subcategories
  article_identities article_variant_identities article_mugs article_mug_variants
  article_spod_articles article_spod_appearances article_spod_images
  article_spod_size_measurements article_spod_variants
  prompt_categories prompt_ordering prompt_subcategories prompt_slots
  prompt_slot_variants prompts prompt_slot_variant_mappings
  production_destinations production_destination_sftp production_destination_spod
)
WIPE_TABLES=(
  carts cart_items orders order_items order_events payments
  promotion_redemptions promotion_reservations
  production_requests production_jobs production_job_items production_deliveries
  production_spod_orders production_spod_designs
  email_jobs operations_findings
)
KEEP_TABLES=(
  schema_migrations users user_roles account_tokens magic_coins print_images
  operations_last_scan
)

SOURCE=""
TARGET=""
YES=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --source) SOURCE="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    --yes) YES=true; shift ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if [[ -z "$SOURCE" || -z "$TARGET" ]]; then
  echo "Usage: $0 --source <connection_string> --target <connection_string> [--yes]"
  echo ""
  echo "Example:"
  echo "  $0 \\"
  echo "    --source 'postgresql://voenix:secret@db.example.com:5432/voenix' \\"
  echo "    --target 'postgresql://voenix:voenix@localhost:5432/voenix'"
  echo ""
  echo "--yes skips the confirmation before the target's data is replaced."
  exit 1
fi

# Every source session is read-only, enforced by PostgreSQL itself.
psql_source() {
  PGOPTIONS="-c default_transaction_read_only=on" psql -X -v ON_ERROR_STOP=1 "$SOURCE" "$@"
}
psql_target() { psql -X -v ON_ERROR_STOP=1 "$TARGET" "$@"; }

echo "Checking source database..."
psql_source -c "SELECT 1" > /dev/null 2>&1 || { echo "ERROR: Cannot connect to source database"; exit 1; }
echo "Checking target database..."
psql_target -c "SELECT 1" > /dev/null 2>&1 || { echo "ERROR: Cannot connect to target database"; exit 1; }

for side in source target; do
  if [[ "$("psql_$side" -t -A -c "SELECT to_regclass('voenix.schema_migrations') IS NOT NULL;")" != "t" ]]; then
    echo "ERROR: The $side database has no table voenix.schema_migrations. Is it the right"
    echo "       database (the one in the connection string's path), migrated by the backend?"
    exit 1
  fi
done

# The copy only works when both schemas are identical, that is, when both sides
# applied exactly the same migrations.
MIGRATIONS_QUERY="SELECT version || ' ' || name FROM voenix.schema_migrations ORDER BY version;"
if ! diff <(psql_source -t -A -c "$MIGRATIONS_QUERY") <(psql_target -t -A -c "$MIGRATIONS_QUERY"); then
  echo "ERROR: Source (<) and target (>) applied different migrations. Start the backend"
  echo "       of the matching version against the older database first."
  exit 1
fi
echo "Both databases applied the same migrations."

# A table a new migration adds must be sorted into one of the three lists
# before the script copies anything again.
LISTED=" ${COPY_TABLES[*]} ${WIPE_TABLES[*]} ${KEEP_TABLES[*]} "
UNLISTED=$(psql_target -t -A -c "SELECT tablename FROM pg_tables WHERE schemaname = 'voenix' ORDER BY 1;" \
  | while read -r table; do [[ "$LISTED" == *" $table "* ]] || echo "$table"; done)
if [[ -n "$UNLISTED" ]]; then
  echo "ERROR: These tables are in none of COPY_TABLES, WIPE_TABLES, KEEP_TABLES:"
  echo "$UNLISTED"
  exit 1
fi
echo ""

TARGET_NAME=$(psql_target -t -A -c "SELECT current_database() || ' on ' || COALESCE(inet_server_addr()::text, 'local socket') || ':' || inet_server_port();")
if ! $YES; then
  echo "In database $TARGET_NAME, the catalog and operations data is"
  echo "replaced, and carts, orders, payments, and production jobs are deleted."
  echo "User accounts stay."
  read -r -p "Type the database name to continue: " answer
  [[ "$answer" == "${TARGET_NAME%% on *}" ]] || { echo "Aborted."; exit 1; }
fi

# users (kept) references suppliers, and PostgreSQL refuses to TRUNCATE a table
# that a kept table references. suppliers and the countries it references are
# therefore emptied with DELETE, after the supplier links are set aside.
TRUNCATE_LIST=""
for table in "${COPY_TABLES[@]}" "${WIPE_TABLES[@]}"; do
  [[ "$table" == suppliers || "$table" == countries ]] && continue
  TRUNCATE_LIST+="${TRUNCATE_LIST:+, }voenix.$table"
done
PREPARE_SQL="
  CREATE TEMP TABLE supplier_logins ON COMMIT DROP AS
    SELECT id, supplier_id FROM voenix.users WHERE supplier_id IS NOT NULL;
  UPDATE voenix.users SET supplier_id = NULL WHERE supplier_id IS NOT NULL;
  TRUNCATE TABLE $TRUNCATE_LIST;
  DELETE FROM voenix.suppliers;
  DELETE FROM voenix.countries;"
RELINK_SQL="
  UPDATE voenix.users u SET supplier_id = l.supplier_id
  FROM pg_temp.supplier_logins l JOIN voenix.suppliers s ON s.id = l.supplier_id
  WHERE u.id = l.id;"

# Dump to a file first: a dump that breaks off mid-stream must not reach the
# target, where psql would commit the emptied tables with half their data.
DUMP_FILE=$(mktemp)
trap 'rm -f "$DUMP_FILE"' EXIT

echo "Dumping source data..."
# pg_dump orders the COPY blocks by foreign keys and sets the identity
# sequences of the dumped tables to the source's values.
DUMP_TABLES=()
for table in "${COPY_TABLES[@]}"; do DUMP_TABLES+=(--table="voenix.$table"); done
PGOPTIONS="-c default_transaction_read_only=on" \
  pg_dump --data-only "${DUMP_TABLES[@]}" --no-owner --no-privileges --file="$DUMP_FILE" "$SOURCE"

echo "Replacing target data..."
# The deferred unique rules are checked at commit.
psql_target --single-transaction -q -c "$PREPARE_SQL" -f "$DUMP_FILE" -c "$RELINK_SQL" > /dev/null
echo "Data copied."
echo ""

# Verification: every copied table must hold the same number of rows on both sides.
COPY_TABLES_SQL="'{$(IFS=,; echo "${COPY_TABLES[*]}")}'"
COUNTS_QUERY="
  SELECT table_name || ' ' || (xpath('/row/c/text()',
    query_to_xml(format('SELECT COUNT(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]
  FROM information_schema.tables
  WHERE table_schema = 'voenix' AND table_name = ANY($COPY_TABLES_SQL::text[])
  ORDER BY table_name;"
if diff <(psql_source -t -A -c "$COUNTS_QUERY") <(psql_target -t -A -c "$COUNTS_QUERY"); then
  echo "Copy completed. All ${#COPY_TABLES[@]} copied tables have matching row counts."
  echo "Reminder: stored files (images, PDFs) are not part of the database."
else
  echo "WARNING: Row counts differ between source (<) and target (>)."
  exit 1
fi
