#!/usr/bin/env bash
# Copies the catalog data of a legacy backend into the Kotlin backend's
# database. Two source schemas are recognized automatically:
#
#   v1     — the Go backend (app.voenix.shop), the version that is live.
#   dotnet — the .NET backend (voenix-shop) with all of its migrations applied.
#
# Target is a database that Flyway has already migrated (schema `voenix`).
#
# What is copied: VAT entries, suppliers, prices, article categories and
# subcategories, mugs with details and variants, prompt categories and
# subcategories, prompt slots with variants, prompts, and the prompt-to-slot-
# variant mappings. Users, carts, orders, payments, coupons, and image files
# are NOT copied.
#
# The target tables are truncated first. TRUNCATE ... CASCADE also empties
# every table that references them, so carts, orders, and payments in the
# target database are wiped as well.
#
# See docs/dev/backend/importing-legacy-catalog-data.md for the full story.
set -euo pipefail

SOURCE=""
TARGET=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --source) SOURCE="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if [[ -z "$SOURCE" || -z "$TARGET" ]]; then
  echo "Usage: $0 --source <connection_string> --target <connection_string>"
  echo ""
  echo "Example:"
  echo "  $0 \\"
  echo "    --source 'postgresql://voenix:voenix@localhost:5432/voenix_v1' \\"
  echo "    --target 'postgresql://voenix:voenix@localhost:5432/voenix'"
  exit 1
fi

# Every source session is opened read-only, enforced by PostgreSQL itself: a
# write on the source connection would fail with "cannot execute ... in a
# read-only transaction" instead of changing anything.
psql_source() {
  PGOPTIONS="-c default_transaction_read_only=on" psql -X -v ON_ERROR_STOP=1 "$SOURCE" "$@"
}
psql_target() { psql -X -v ON_ERROR_STOP=1 "$TARGET" "$@"; }

echo "=== Data Migration: legacy voenix → Kotlin voenix ==="
echo ""

# Pre-flight checks
echo "Checking source database..."
psql_source -c "SELECT 1" > /dev/null 2>&1 || { echo "ERROR: Cannot connect to source database"; exit 1; }
echo "Checking target database..."
psql_target -c "SELECT 1" > /dev/null 2>&1 || { echo "ERROR: Cannot connect to target database"; exit 1; }
echo "Both databases reachable."
echo ""

table_exists() {
  local connection="$1"
  local table="$2"

  psql -X -v ON_ERROR_STOP=1 "$connection" -t -A -c "
    SELECT EXISTS (
      SELECT 1
      FROM information_schema.tables
      WHERE table_schema = 'voenix'
        AND table_name = '$table'
    );
  "
}

column_exists() {
  local connection="$1"
  local table="$2"
  local column="$3"

  psql -X -v ON_ERROR_STOP=1 "$connection" -t -A -c "
    SELECT EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = 'voenix'
        AND table_name = '$table'
        AND column_name = '$column'
    );
  "
}

# Recognize the source schema. The v1 backend names its subcategory table
# article_sub_categories; the .NET backend renamed it to article_subcategories
# and, in its final state, gave articles a position column.
if [[ "$(table_exists "$SOURCE" article_sub_categories)" == "t" ]]; then
  SOURCE_SCHEMA="v1"
  if [[ "$(column_exists "$SOURCE" prices sales_vat_rate_percent)" != "t" ]]; then
    echo "ERROR: The source looks like v1, but prices.sales_vat_rate_percent is"
    echo "       missing. This script only knows the fully migrated v1 schema."
    exit 1
  fi
elif [[ "$(column_exists "$SOURCE" articles position)" == "t" ]]; then
  SOURCE_SCHEMA="dotnet"
else
  echo "ERROR: Unrecognized source schema. Expected either the v1 schema"
  echo "       (table article_sub_categories) or the final .NET schema"
  echo "       (column articles.position)."
  exit 1
fi
echo "Source schema recognized: $SOURCE_SCHEMA"
echo ""

if [[ "$(column_exists "$TARGET" article_identities article_type)" != "t" ]]; then
  echo "ERROR: The target database has no article_identities table."
  echo "       Start the Kotlin backend once so Flyway creates the schema."
  exit 1
fi

check_source_has_no_rows() {
  local label="$1"
  local query="$2"
  local rows

  rows=$(psql_source -t -A -c "$query")
  if [[ -n "$rows" ]]; then
    echo "ERROR: $label"
    echo "$rows"
    exit 1
  fi
}

# The two backends store the article subcategories under different names.
if [[ "$SOURCE_SCHEMA" == "v1" ]]; then
  SRC_ARTICLE_SUBCATS="article_sub_categories"
else
  SRC_ARTICLE_SUBCATS="article_subcategories"
fi

# v1 links a price to its article through prices.article_id; the .NET backend
# turned that around into articles.price_id. Only prices that a migrated mug
# or a migrated prompt actually references are copied.
if [[ "$SOURCE_SCHEMA" == "v1" ]]; then
  PRICE_FILTER_SQL="
    (p.article_id IN (SELECT a.id FROM voenix.articles a WHERE a.article_type = 'MUG')
     OR p.id IN (SELECT pr.price_id FROM voenix.prompts pr
                 WHERE pr.price_id IS NOT NULL AND pr.category_id IS NOT NULL))"
else
  PRICE_FILTER_SQL="
    (p.id IN (SELECT a.price_id FROM voenix.articles a WHERE a.price_id IS NOT NULL)
     OR p.id IN (SELECT pr.price_id FROM voenix.prompts pr
                 WHERE pr.price_id IS NOT NULL AND pr.category_id IS NOT NULL))"
fi

echo "Checking source data against the target schema's rules..."

# The target allows at most one default VAT entry, and percent must be 0-100.
check_source_has_no_rows \
  "value_added_taxes contains more than one default entry" \
  "SELECT id || ': ' || name FROM voenix.value_added_taxes WHERE is_default
   AND 1 < (SELECT COUNT(*) FROM voenix.value_added_taxes WHERE is_default);"
check_source_has_no_rows \
  "value_added_taxes contains percent values outside 0-100" \
  "SELECT id || ': ' || percent FROM voenix.value_added_taxes
   WHERE percent < 0 OR percent > 100;"

# The target makes price ownership unique: one price belongs to at most one
# article and at most one prompt, and never to both.
check_source_has_no_rows \
  "prompts share a price_id" \
  "SELECT price_id || ' (' || COUNT(*) || ' prompts)' FROM voenix.prompts
   WHERE price_id IS NOT NULL GROUP BY price_id HAVING COUNT(*) > 1;"

# The target's composite foreign keys demand that a subcategory belongs to the
# row's own category.
check_source_has_no_rows \
  "articles whose subcategory belongs to a different category" \
  "SELECT a.id FROM voenix.articles a
   JOIN voenix.$SRC_ARTICLE_SUBCATS s ON s.id = a.subcategory_id
   WHERE a.article_type = 'MUG'
     AND s.article_category_id IS DISTINCT FROM a.category_id;"
check_source_has_no_rows \
  "prompts whose subcategory belongs to a different category" \
  "SELECT p.id FROM voenix.prompts p
   JOIN voenix.prompt_subcategories s ON s.id = p.subcategory_id
   WHERE p.category_id IS NOT NULL
     AND s.prompt_category_id IS DISTINCT FROM p.category_id;"

# The target allows at most one default variant per article.
check_source_has_no_rows \
  "articles with more than one default variant" \
  "SELECT article_id || ' (' || COUNT(*) || ' defaults)' FROM voenix.article_mug_variants
   WHERE is_default GROUP BY article_id HAVING COUNT(*) > 1;"

# Names must be unique case-insensitively in the target.
check_source_has_no_rows \
  "article_categories contains case-insensitive duplicate names" \
  "SELECT lower(name) || ' (' || COUNT(*) || ' rows)' FROM voenix.article_categories
   GROUP BY lower(name) HAVING COUNT(*) > 1;"
check_source_has_no_rows \
  "$SRC_ARTICLE_SUBCATS contains case-insensitive duplicate names within a category" \
  "SELECT article_category_id || '/' || lower(name) || ' (' || COUNT(*) || ' rows)'
   FROM voenix.$SRC_ARTICLE_SUBCATS
   GROUP BY article_category_id, lower(name) HAVING COUNT(*) > 1;"
check_source_has_no_rows \
  "prompt_categories contains case-insensitive duplicate names" \
  "SELECT lower(name) || ' (' || COUNT(*) || ' rows)' FROM voenix.prompt_categories
   GROUP BY lower(name) HAVING COUNT(*) > 1;"
check_source_has_no_rows \
  "prompt_subcategories contains case-insensitive duplicate names within a category" \
  "SELECT prompt_category_id || '/' || lower(name) || ' (' || COUNT(*) || ' rows)'
   FROM voenix.prompt_subcategories
   GROUP BY prompt_category_id, lower(name) HAVING COUNT(*) > 1;"
check_source_has_no_rows \
  "prompt_slot_types contains case-insensitive duplicate names" \
  "SELECT lower(name) || ' (' || COUNT(*) || ' rows)' FROM voenix.prompt_slot_types
   GROUP BY lower(name) HAVING COUNT(*) > 1;"
check_source_has_no_rows \
  "prompt_slot_variants contains case-insensitive duplicate names" \
  "SELECT lower(name) || ' (' || COUNT(*) || ' rows)' FROM voenix.prompt_slot_variants
   GROUP BY lower(name) HAVING COUNT(*) > 1;"
check_source_has_no_rows \
  "prompt_slot_variants contains blank prompts" \
  "SELECT id FROM voenix.prompt_slot_variants WHERE prompt IS NULL OR btrim(prompt) = '';"

# The source stores several of these values in wider or unbounded columns; the
# target bounds them. Better one readable list now than a COPY abort halfway
# through.
check_source_has_no_rows \
  "article_categories contains values longer than the target columns allow" \
  "SELECT id FROM voenix.article_categories
   WHERE length(name) > 200 OR length(description) > 1000;"
check_source_has_no_rows \
  "$SRC_ARTICLE_SUBCATS contains values longer than the target columns allow" \
  "SELECT id FROM voenix.$SRC_ARTICLE_SUBCATS
   WHERE length(name) > 200 OR length(description) > 1000;"
check_source_has_no_rows \
  "articles contains values longer than the target columns allow" \
  "SELECT id FROM voenix.articles
   WHERE article_type = 'MUG'
     AND (length(name) > 255 OR length(description_short) > 1000
       OR length(description_long) > 5000
       OR length(supplier_article_name) > 255
       OR length(supplier_article_number) > 255);"
check_source_has_no_rows \
  "article_mug_details contains values longer than the target columns allow" \
  "SELECT article_id FROM voenix.article_mug_details WHERE length(filling_quantity) > 255;"
check_source_has_no_rows \
  "article_mug_variants contains values longer than the target columns allow" \
  "SELECT id FROM voenix.article_mug_variants
   WHERE length(name) > 255 OR length(inside_color_code) > 255
      OR length(outside_color_code) > 255 OR length(example_image_filename) > 255;"
check_source_has_no_rows \
  "prompt_categories contains values longer than the target columns allow" \
  "SELECT id FROM voenix.prompt_categories WHERE length(name) > 200;"
check_source_has_no_rows \
  "prompt_slot_variants contains values longer than the target columns allow" \
  "SELECT id FROM voenix.prompt_slot_variants
   WHERE length(description) > 1000 OR length(llm) > 255;"
check_source_has_no_rows \
  "prompts contains values longer than the target columns allow" \
  "SELECT id FROM voenix.prompts
   WHERE category_id IS NOT NULL
     AND (length(title) > 255 OR length(example_image_filename) > 255
       OR length(llm) > 255);"

# The target requires a supplier name.
check_source_has_no_rows \
  "suppliers without a name" \
  "SELECT id FROM voenix.suppliers WHERE name IS NULL OR btrim(name) = '';"

if [[ "$SOURCE_SCHEMA" == "v1" ]]; then
  # v1 prices carry their VAT as a percent value, so the migrated price finds
  # its VAT row by percent. That only works when percents are unambiguous.
  check_source_has_no_rows \
    "value_added_taxes contains duplicate percent values; price VAT mapping would be ambiguous" \
    "SELECT percent || ' (' || COUNT(*) || ' rows)' FROM voenix.value_added_taxes
     GROUP BY percent HAVING COUNT(*) > 1;"
  check_source_has_no_rows \
    "prices contain sales VAT percent values without a matching value_added_taxes row" \
    "SELECT DISTINCT p.id || ': ' || p.sales_vat_rate_percent FROM voenix.prices p
     LEFT JOIN voenix.value_added_taxes v ON v.percent = p.sales_vat_rate_percent
     WHERE v.id IS NULL AND $PRICE_FILTER_SQL;"
  check_source_has_no_rows \
    "prices that belong to an article and are also referenced by a prompt" \
    "SELECT p.id FROM voenix.prices p
     JOIN voenix.prompts pr ON pr.price_id = p.id
     WHERE pr.category_id IS NOT NULL
       AND p.article_id IN (SELECT a.id FROM voenix.articles a WHERE a.article_type = 'MUG');"
else
  # The .NET backend never enforced article price uniqueness in the database.
  check_source_has_no_rows \
    "articles share a price_id" \
    "SELECT price_id || ' (' || COUNT(*) || ' articles)' FROM voenix.articles
     WHERE price_id IS NOT NULL GROUP BY price_id HAVING COUNT(*) > 1;"
  # The .NET schema only ever held MUG articles; anything else is unexpected.
  check_source_has_no_rows \
    "articles contain types other than MUG" \
    "SELECT id || ': ' || article_type FROM voenix.articles WHERE article_type <> 'MUG';"
fi

echo "Source data checks passed."
echo ""

# Suppliers reference countries. The target seeds its own country rows, so the
# copy maps a supplier's country instead of trusting ids: v1 countries only
# have a name, the .NET countries have a country_code.
if [[ "$SOURCE_SCHEMA" == "v1" ]]; then
  TARGET_COUNTRY_MATCH_SQL="lower(name)"
  SOURCE_COUNTRY_MATCH_SQL="lower(c.name)"
else
  TARGET_COUNTRY_MATCH_SQL="country_code"
  SOURCE_COUNTRY_MATCH_SQL="c.country_code"
fi

build_target_country_values_sql() {
  local values

  values=$(
    psql_target -t -A -F "," -c "
      SELECT id, $TARGET_COUNTRY_MATCH_SQL
      FROM voenix.countries
      ORDER BY id;
    " | awk -F, '
      NF == 2 {
        if (out != "") {
          out = out ", "
        }
        out = out "(" $1 "::bigint, '\''" $2 "'\'')"
      }
      END {
        print out
      }
    '
  )

  if [[ -z "$values" ]]; then
    echo "ERROR: target countries table contains no rows; did Flyway run?"
    exit 1
  fi

  printf '%s' "$values"
}

TARGET_COUNTRY_VALUES_SQL=$(build_target_country_values_sql)

check_source_has_no_rows \
  "suppliers reference countries that are missing in the target" \
  "WITH target_countries(id, match_value) AS (VALUES $TARGET_COUNTRY_VALUES_SQL)
   SELECT s.id || ': ' || c.name FROM voenix.suppliers s
   JOIN voenix.countries c ON c.id = s.country_id
   LEFT JOIN target_countries tc ON tc.match_value = $SOURCE_COUNTRY_MATCH_SQL
   WHERE tc.id IS NULL;"

# The target knows only MUG articles. v1 additionally holds the internal
# CREDIT article for Magic Coins; the Kotlin backend has its own Magic Coins
# module, so that article, its variants, and its price are skipped.
if [[ "$SOURCE_SCHEMA" == "v1" ]]; then
  SKIPPED_ARTICLES=$(psql_source -t -A -c "
    SELECT id || ': ' || name || ' (' || article_type || ')'
    FROM voenix.articles WHERE article_type <> 'MUG';
  ")
  if [[ -n "$SKIPPED_ARTICLES" ]]; then
    echo "NOTICE: These articles are not mugs and are skipped (the Kotlin"
    echo "        backend models Magic Coins without a catalog article):"
    echo "$SKIPPED_ARTICLES"
    echo ""
  fi
fi

# The target requires a category on every prompt. Prompts without one are
# skipped, together with their slot-variant mappings.
SKIPPED_PROMPTS=$(psql_source -t -A -c "
  SELECT id || ': ' || title FROM voenix.prompts WHERE category_id IS NULL;
")
if [[ -n "$SKIPPED_PROMPTS" ]]; then
  echo "NOTICE: These prompts have no category and are skipped:"
  echo "$SKIPPED_PROMPTS"
  echo ""
fi

# Articles that are active but incomplete cannot stay active: the target
# demands that an active article has a price, a category, and its mug details.
# The legacy storefront silently hid such articles, so importing them as
# inactive preserves what customers actually saw. Report them, then demote.
if [[ "$SOURCE_SCHEMA" == "v1" ]]; then
  DEMOTION_QUERY="
    SELECT a.id || ': ' || a.name
    FROM voenix.articles a
    LEFT JOIN voenix.prices p ON p.article_id = a.id
    LEFT JOIN voenix.article_mug_details d ON d.article_id = a.id
    WHERE a.article_type = 'MUG' AND COALESCE(a.active, true)
      AND (p.id IS NULL OR a.category_id IS NULL OR d.article_id IS NULL);"
else
  DEMOTION_QUERY="
    SELECT a.id || ': ' || a.name
    FROM voenix.articles a
    LEFT JOIN voenix.article_mug_details d ON d.article_id = a.id
    WHERE a.active
      AND (a.price_id IS NULL OR a.category_id IS NULL OR d.article_id IS NULL);"
fi
DEMOTED_ARTICLES=$(psql_source -t -A -c "$DEMOTION_QUERY")
if [[ -n "$DEMOTED_ARTICLES" ]]; then
  echo "NOTICE: These active articles lack a price, a category, or mug details."
  echo "        The target schema forbids that, so they are imported as inactive:"
  echo "$DEMOTED_ARTICLES"
  echo ""
fi

# Per-schema SELECT statements. The v1 backend has no position columns on
# categories, subcategories, articles, and prompts, so the import invents
# them from the id order (creation order). Slot positions exist in v1 but may
# start at 0, which the target forbids, so they are renumbered.
if [[ "$SOURCE_SCHEMA" == "v1" ]]; then
  SUPPLIERS_SELECT="
    WITH target_countries(id, match_value) AS (VALUES $TARGET_COUNTRY_VALUES_SQL)
    SELECT s.id, s.name, s.title, s.first_name, s.last_name, s.street, s.house_number,
           s.city, s.postal_code::text, tc.id, s.phone_number1, s.phone_number2,
           s.phone_number3, s.email, s.website
    FROM voenix.suppliers s
    LEFT JOIN voenix.countries c ON c.id = s.country_id
    LEFT JOIN target_countries tc ON tc.match_value = lower(c.name)"

  # The old v1-to-.NET migration already decided how a v1 price maps onto the
  # admin calculator model: keep the sales gross total, reset the purchase
  # side, and take both VAT references from the sales VAT percent. This keeps
  # that decision.
  PRICES_SELECT="
    SELECT p.id, sv.id, sv.id, 'NET', 'COST', 0, 0, 0,
           'GROSS', 'TOTAL', 0, 0, COALESCE(p.sales_total_gross, 0)
    FROM voenix.prices p
    JOIN voenix.value_added_taxes sv ON sv.percent = p.sales_vat_rate_percent
    WHERE $PRICE_FILTER_SQL"

  ARTICLE_CATEGORIES_SELECT="
    SELECT id, name, description, ROW_NUMBER() OVER (ORDER BY id), true
    FROM voenix.article_categories"

  ARTICLE_SUBCATEGORIES_SELECT="
    SELECT id, article_category_id, name, description, NULL,
           ROW_NUMBER() OVER (PARTITION BY article_category_id ORDER BY id), true
    FROM voenix.article_sub_categories"

  ARTICLE_IDENTITIES_SELECT="
    SELECT id, article_type FROM voenix.articles WHERE article_type = 'MUG'"

  ARTICLE_MUGS_SELECT="
    SELECT a.id, a.article_type, ROW_NUMBER() OVER (ORDER BY a.id),
           a.name, a.description_short, a.description_long,
           (COALESCE(a.active, true) AND p.id IS NOT NULL
              AND a.category_id IS NOT NULL AND d.article_id IS NOT NULL),
           a.category_id, a.subcategory_id, a.supplier_id, a.supplier_article_name,
           a.supplier_article_number, p.id,
           d.height_mm, d.diameter_mm, d.print_template_width_mm, d.print_template_height_mm,
           d.filling_quantity,
           CASE WHEN d.article_id IS NOT NULL THEN COALESCE(d.dishwasher_safe, true) END,
           d.document_format_width_mm, d.document_format_height_mm,
           d.document_format_margin_bottom_mm
    FROM voenix.articles a
    LEFT JOIN voenix.prices p ON p.article_id = a.id
    LEFT JOIN voenix.article_mug_details d ON d.article_id = a.id
    WHERE a.article_type = 'MUG'"

  MUG_VARIANT_FROM="
    FROM voenix.article_mug_variants v
    JOIN voenix.articles a ON a.id = v.article_id AND a.article_type = 'MUG'"

  PROMPT_CATEGORIES_SELECT="
    SELECT id, name, ROW_NUMBER() OVER (ORDER BY id), true
    FROM voenix.prompt_categories"

  PROMPT_SUBCATEGORIES_SELECT="
    SELECT id, prompt_category_id, name, description,
           ROW_NUMBER() OVER (PARTITION BY prompt_category_id ORDER BY id), true
    FROM voenix.prompt_subcategories"

  PROMPT_SLOTS_SELECT="
    SELECT id, name, ROW_NUMBER() OVER (ORDER BY position, id)
    FROM voenix.prompt_slot_types"

  PROMPTS_SELECT="
    SELECT id, ROW_NUMBER() OVER (ORDER BY id), title, COALESCE(prompt_text, ''),
           category_id, subcategory_id, example_image_filename, price_id, llm,
           active, archived
    FROM voenix.prompts
    WHERE category_id IS NOT NULL"
else
  SUPPLIERS_SELECT="
    WITH target_countries(id, match_value) AS (VALUES $TARGET_COUNTRY_VALUES_SQL)
    SELECT s.id, s.name, s.title, s.first_name, s.last_name, s.street, s.house_number,
           s.city, s.postal_code, tc.id, s.phone_number1, s.phone_number2,
           s.phone_number3, s.email, s.website
    FROM voenix.suppliers s
    LEFT JOIN voenix.countries c ON c.id = s.country_id
    LEFT JOIN target_countries tc ON tc.match_value = c.country_code"

  PRICES_SELECT="
    SELECT p.id, p.purchase_vat_id, p.sales_vat_id, p.purchase_calculation_mode,
           p.purchase_active_row, p.purchase_price_input_cents, p.purchase_cost_input_cents,
           p.purchase_cost_percent, p.sales_calculation_mode, p.sales_active_row,
           p.sales_margin_input_cents, p.sales_margin_percent, p.sales_total_input_cents
    FROM voenix.prices p
    WHERE $PRICE_FILTER_SQL"

  ARTICLE_CATEGORIES_SELECT="
    SELECT id, name, description, position, active FROM voenix.article_categories"

  ARTICLE_SUBCATEGORIES_SELECT="
    SELECT id, article_category_id, name, description, example_image_filename, position, active
    FROM voenix.article_subcategories"

  ARTICLE_IDENTITIES_SELECT="
    SELECT id, article_type FROM voenix.articles"

  ARTICLE_MUGS_SELECT="
    SELECT a.id, a.article_type, a.position, a.name, a.description_short, a.description_long,
           (a.active AND a.price_id IS NOT NULL AND a.category_id IS NOT NULL
              AND d.article_id IS NOT NULL),
           a.category_id, a.subcategory_id, a.supplier_id, a.supplier_article_name,
           a.supplier_article_number, a.price_id,
           d.height_mm, d.diameter_mm, d.print_template_width_mm, d.print_template_height_mm,
           d.filling_quantity, d.dishwasher_safe, d.document_format_width_mm,
           d.document_format_height_mm, d.document_format_margin_bottom_mm
    FROM voenix.articles a
    LEFT JOIN voenix.article_mug_details d ON d.article_id = a.id"

  MUG_VARIANT_FROM="
    FROM voenix.article_mug_variants v"

  PROMPT_CATEGORIES_SELECT="
    SELECT id, name, position, active FROM voenix.prompt_categories"

  PROMPT_SUBCATEGORIES_SELECT="
    SELECT id, prompt_category_id, name, description, position, active
    FROM voenix.prompt_subcategories"

  PROMPT_SLOTS_SELECT="
    SELECT id, name, position FROM voenix.prompt_slot_types"

  PROMPTS_SELECT="
    SELECT id, position, title, prompt_text, category_id, subcategory_id,
           example_image_filename, price_id, llm, active, archived
    FROM voenix.prompts
    WHERE category_id IS NOT NULL"
fi

copy_table() {
  local label="$1"
  local select_query="$2"
  local target_table="$3"
  local target_columns="$4"

  echo -n "  Migrating $label..."
  psql_source -c "COPY ($select_query) TO STDOUT WITH (FORMAT csv, NULL '')" \
    | psql_target -c "COPY voenix.$target_table ($target_columns) FROM STDIN WITH (FORMAT csv, NULL '')"
  echo " done"
}

# Truncate target tables. CASCADE also empties every referencing table, so
# carts, orders, and payments in the target database are wiped along the way.
echo "Truncating target tables (CASCADE also empties carts, orders, payments)..."
psql_target -c "
  TRUNCATE TABLE
    voenix.prompt_slot_variant_mappings,
    voenix.prompt_slot_variants,
    voenix.prompt_slots,
    voenix.prompts,
    voenix.prompt_subcategories,
    voenix.prompt_categories,
    voenix.article_mug_variants,
    voenix.article_variant_identities,
    voenix.article_mugs,
    voenix.article_identities,
    voenix.article_subcategories,
    voenix.article_categories,
    voenix.prices,
    voenix.suppliers,
    voenix.value_added_taxes
  CASCADE;
"
echo "Tables truncated."
echo ""

# Migrate tables in FK dependency order
echo "Migrating tables..."

# 1. value_added_taxes (no deps; same shape in every schema)
copy_table "value_added_taxes" \
  "SELECT id, name, percent, description, is_default FROM voenix.value_added_taxes" \
  "value_added_taxes" \
  "id, name, percent, description, is_default"

# 2. suppliers (→ countries, mapped by code or name)
copy_table "suppliers" \
  "$SUPPLIERS_SELECT" \
  "suppliers" \
  "id, name, title, first_name, last_name, street, house_number, city, postal_code, country_id, phone_number1, phone_number2, phone_number3, email, website"

# 3. prices (→ value_added_taxes; VAT ids were copied unchanged in step 1)
copy_table "prices" \
  "$PRICES_SELECT" \
  "prices" \
  "id, purchase_vat_id, sales_vat_id, purchase_calculation_mode, purchase_active_row, purchase_price_input_cents, purchase_cost_input_cents, purchase_cost_percent, sales_calculation_mode, sales_active_row, sales_margin_input_cents, sales_margin_percent, sales_total_input_cents"

# 4. article_categories (no deps)
copy_table "article_categories" \
  "$ARTICLE_CATEGORIES_SELECT" \
  "article_categories" \
  "id, name, description, position, active"

# 5. article_subcategories (→ article_categories; column renamed to category_id)
copy_table "article_subcategories" \
  "$ARTICLE_SUBCATEGORIES_SELECT" \
  "article_subcategories" \
  "id, category_id, name, description, example_image_filename, position, active"

# 6. article_identities (the target's identity registry)
copy_table "article_identities" \
  "$ARTICLE_IDENTITIES_SELECT" \
  "article_identities" \
  "id, article_type"

# 7. article_mugs (the legacy articles row and its article_mug_details row, merged)
copy_table "article_mugs" \
  "$ARTICLE_MUGS_SELECT" \
  "article_mugs" \
  "id, article_type, position, name, description_short, description_long, active, category_id, subcategory_id, supplier_id, supplier_article_name, supplier_article_number, price_id, height_mm, diameter_mm, print_template_width_mm, print_template_height_mm, filling_quantity, dishwasher_safe, document_format_width_mm, document_format_height_mm, document_format_margin_bottom_mm"

# 8. article_variant_identities (the target's variant identity registry)
copy_table "article_variant_identities" \
  "SELECT v.id, v.article_id, 'MUG' $MUG_VARIANT_FROM" \
  "article_variant_identities" \
  "id, article_id, article_type"

# 9. article_mug_variants (→ article_mugs)
copy_table "article_mug_variants" \
  "SELECT v.id, v.article_id, v.inside_color_code, v.outside_color_code, v.name,
          v.is_default, v.active, v.example_image_filename $MUG_VARIANT_FROM" \
  "article_mug_variants" \
  "id, article_id, inside_color_code, outside_color_code, name, is_default, active, example_image_filename"

# 10. prompt_categories (no deps)
copy_table "prompt_categories" \
  "$PROMPT_CATEGORIES_SELECT" \
  "prompt_categories" \
  "id, name, position, active"

# 11. prompt_subcategories (→ prompt_categories; column renamed to category_id)
copy_table "prompt_subcategories" \
  "$PROMPT_SUBCATEGORIES_SELECT" \
  "prompt_subcategories" \
  "id, category_id, name, description, position, active"

# 12. prompt_slots (source table name: prompt_slot_types)
copy_table "prompt_slots" \
  "$PROMPT_SLOTS_SELECT" \
  "prompt_slots" \
  "id, name, position"

# 13. prompt_slot_variants (→ prompt_slots; column renamed to slot_id)
copy_table "prompt_slot_variants" \
  "SELECT id, slot_type_id, name, prompt, description, llm FROM voenix.prompt_slot_variants" \
  "prompt_slot_variants" \
  "id, slot_id, name, prompt, description, llm"

# 14. prompts (→ prompt_categories, prompt_subcategories, prices)
copy_table "prompts" \
  "$PROMPTS_SELECT" \
  "prompts" \
  "id, position, title, prompt_text, category_id, subcategory_id, example_image_filename, price_id, llm, active, archived"

# 15. prompt_slot_variant_mappings (→ prompts, prompt_slot_variants; skipped
#     prompts take their mappings with them)
copy_table "prompt_slot_variant_mappings" \
  "SELECT m.prompt_id, m.slot_id FROM voenix.prompt_slot_variant_mappings m
   JOIN voenix.prompts p ON p.id = m.prompt_id AND p.category_id IS NOT NULL" \
  "prompt_slot_variant_mappings" \
  "prompt_id, slot_variant_id"

echo ""
echo "All tables migrated."
echo ""

# Reset identity sequences to the copied ids, so the next insert does not
# collide with an imported row.
echo "Resetting sequences..."
for table in value_added_taxes suppliers prices article_categories article_subcategories \
             article_identities article_variant_identities \
             prompt_categories prompt_subcategories prompt_slots prompt_slot_variants prompts; do
  psql_target -c "
    SELECT setval(
      pg_get_serial_sequence('voenix.$table', 'id'),
      GREATEST(COALESCE(MAX(id), 0), 1)
    ) FROM voenix.$table;
  " > /dev/null
done
echo "Sequences reset."
echo ""

# Verification: compare row counts. The source counts apply the same filters
# as the copies (only mugs, only prompts with a category, only used prices).
echo "=== Verification: Row Counts ==="
printf "%-42s %10s %10s %s\n" "Source → Target" "Source" "Target" "Status"
printf "%-42s %10s %10s %s\n" "---------------" "------" "------" "------"

all_ok=true
verify_counts() {
  local label="$1"
  local source_count_query="$2"
  local target_count_query="$3"

  src_count=$(psql_source -t -A -c "$source_count_query")
  tgt_count=$(psql_target -t -A -c "$target_count_query")
  if [[ "$src_count" == "$tgt_count" ]]; then
    status="OK"
  else
    status="MISMATCH"
    all_ok=false
  fi
  printf "%-42s %10s %10s %s\n" "$label" "$src_count" "$tgt_count" "$status"
}

verify_counts "value_added_taxes → value_added_taxes" \
  "SELECT COUNT(*) FROM voenix.value_added_taxes" \
  "SELECT COUNT(*) FROM voenix.value_added_taxes"
verify_counts "suppliers → suppliers" \
  "SELECT COUNT(*) FROM voenix.suppliers" \
  "SELECT COUNT(*) FROM voenix.suppliers"
verify_counts "prices (used) → prices" \
  "SELECT COUNT(*) FROM voenix.prices p WHERE $PRICE_FILTER_SQL" \
  "SELECT COUNT(*) FROM voenix.prices"
verify_counts "article_categories → article_categories" \
  "SELECT COUNT(*) FROM voenix.article_categories" \
  "SELECT COUNT(*) FROM voenix.article_categories"
verify_counts "$SRC_ARTICLE_SUBCATS → article_subcategories" \
  "SELECT COUNT(*) FROM voenix.$SRC_ARTICLE_SUBCATS" \
  "SELECT COUNT(*) FROM voenix.article_subcategories"
verify_counts "articles (mugs) → article_identities" \
  "SELECT COUNT(*) FROM voenix.articles WHERE article_type = 'MUG'" \
  "SELECT COUNT(*) FROM voenix.article_identities"
verify_counts "articles (mugs) → article_mugs" \
  "SELECT COUNT(*) FROM voenix.articles WHERE article_type = 'MUG'" \
  "SELECT COUNT(*) FROM voenix.article_mugs"
verify_counts "article_mug_details → mugs w/ details" \
  "SELECT COUNT(*) FROM voenix.article_mug_details d
   JOIN voenix.articles a ON a.id = d.article_id AND a.article_type = 'MUG'" \
  "SELECT COUNT(*) FROM voenix.article_mugs WHERE height_mm IS NOT NULL"
verify_counts "article_mug_variants → variant identities" \
  "SELECT COUNT(*) $MUG_VARIANT_FROM" \
  "SELECT COUNT(*) FROM voenix.article_variant_identities"
verify_counts "article_mug_variants → article_mug_variants" \
  "SELECT COUNT(*) $MUG_VARIANT_FROM" \
  "SELECT COUNT(*) FROM voenix.article_mug_variants"
verify_counts "prompt_categories → prompt_categories" \
  "SELECT COUNT(*) FROM voenix.prompt_categories" \
  "SELECT COUNT(*) FROM voenix.prompt_categories"
verify_counts "prompt_subcategories → prompt_subcategories" \
  "SELECT COUNT(*) FROM voenix.prompt_subcategories" \
  "SELECT COUNT(*) FROM voenix.prompt_subcategories"
verify_counts "prompt_slot_types → prompt_slots" \
  "SELECT COUNT(*) FROM voenix.prompt_slot_types" \
  "SELECT COUNT(*) FROM voenix.prompt_slots"
verify_counts "prompt_slot_variants → prompt_slot_variants" \
  "SELECT COUNT(*) FROM voenix.prompt_slot_variants" \
  "SELECT COUNT(*) FROM voenix.prompt_slot_variants"
verify_counts "prompts (with category) → prompts" \
  "SELECT COUNT(*) FROM voenix.prompts WHERE category_id IS NOT NULL" \
  "SELECT COUNT(*) FROM voenix.prompts"
verify_counts "mappings (kept prompts) → mappings" \
  "SELECT COUNT(*) FROM voenix.prompt_slot_variant_mappings m
   JOIN voenix.prompts p ON p.id = m.prompt_id AND p.category_id IS NOT NULL" \
  "SELECT COUNT(*) FROM voenix.prompt_slot_variant_mappings"

echo ""
if $all_ok; then
  echo "Migration completed successfully! All row counts match."
  if [[ -n "$SKIPPED_PROMPTS" || -n "$DEMOTED_ARTICLES" ]]; then
    echo "Reminder: check the NOTICE blocks above for skipped prompts and"
    echo "demoted articles."
  fi
else
  echo "WARNING: Some row counts do not match!"
  exit 1
fi
