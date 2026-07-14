# Supplier post-migration to-do list

This list contains work that is intentionally deferred until after the standalone Supplier
migration.

## Frontend list contract

The Kotlin Supplier API intentionally returns `GET /api/admin/suppliers` as a flat JSON array of
complete Supplier values. It does not preserve the C# API's `{ "items": [...] }` wrapper or its
separate list-item representation. Keeping one Supplier representation matches the other simple
Kotlin list endpoints and avoids list-only backend models.

- [ ] When the frontend is migrated, change `fetchSuppliers` to expect an
  `AdminSupplierDetailDto[]` directly instead of `{ items: AdminSupplierListItemDto[] }`.
- [ ] Build the displayed contact person in the frontend from `title`, `firstName`, and
  `lastName`. The existing `formatContactPerson` helper already implements this formatting.
- [ ] Remove the frontend-only `AdminSupplierListItemDto` distinction and use the Supplier detail
  representation for the table, article selector, and store synchronization.

The current production schema intentionally has no `articles` table or Article foreign key. Add
the `InUse` result and its `409 Conflict` mapping together with the real relationship and its
integration test. Do not add a placeholder Article table to the Supplier migration.

## Article relationship

- [ ] When the Article module is migrated, add the nullable `articles.supplier_id` column if it
  does not already exist in the target schema.
- [ ] Add an index on `articles.supplier_id` before enabling the foreign key.
- [ ] Add a foreign key from `articles.supplier_id` to `suppliers.id` with restricted deletion.
  A supplier referenced by an article must not be deleted.
- [ ] Decide whether the production migration needs to preserve legacy article supplier IDs.
  If it does, create placeholder suppliers for missing IDs and advance the supplier identity
  sequence before adding the foreign key. The C# migration used names such as
  `Imported supplier 42`; do not add this compatibility path unless a real data-import scenario
  requires it.
- [ ] Add a PostgreSQL integration test using the real Article schema. It must prove that deleting
  a referenced supplier returns `409 Conflict`, leaves both rows intact, and does not expose a
  database constraint name or message.
- [ ] Add an Article integration test proving that an existing supplier can be assigned and that
  an unknown supplier ID is rejected by the database-backed application flow.
