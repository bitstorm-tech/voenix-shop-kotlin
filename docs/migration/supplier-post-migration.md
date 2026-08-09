# Supplier post-migration to-do list

This list contains work that is intentionally deferred until after the standalone Supplier
migration.

## Frontend list contract

The Kotlin Supplier API intentionally returns `GET /api/admin/suppliers` as a flat JSON array of
complete Supplier values. It does not preserve the C# API's `{ "items": [...] }` wrapper or its
separate list-item representation. Keeping one Supplier representation matches the other simple
Kotlin list endpoints and avoids list-only backend models.

Done on 2026-08-06 with frontend migration ticket #87.

- [x] `fetchSuppliers` expects the bare array directly instead of
  `{ items: AdminSupplierListItemDto[] }`.
- [x] The displayed contact person is built in the frontend from `title`, `firstName`, and
  `lastName`. `formatContactPerson` is exported from `stores/admin/suppliers.ts` and used by
  `AdminSuppliersTable.vue`.
- [x] The frontend-only `AdminSupplierListItemDto` is gone. One type, `AdminSupplierDto` (renamed
  from `AdminSupplierDetailDto`, because there is no second representation left), serves the table,
  the article selector, and store synchronization.

## Article relationship — closed on 2026-07-28 (Article migration T9)

This section previously claimed that the schema had no article foreign key and that
`SupplierDeleteResult.InUse` was therefore unreachable. Two corrections belong in the record:

- the claim was already wrong when it was written. `production_destinations` (V6) and
  `production_jobs` (V8) reference `suppliers.id` with `ON DELETE RESTRICT`, so a supplier used by
  production could always produce the conflict. What was missing was not the outcome, it was a test
  that reached it;
- the article half now exists. `article_mugs.supplier_id` (V13) is a nullable column with a
  restricted foreign key to `suppliers.id`, and every article type table added later declares the
  same reference. There is no `articles` table and no `articles.supplier_id`, because the Article
  migration replaced the single legacy table with one table per article type.

Nothing was needed for legacy supplier ids: the development database is rebuilt from the Flyway
chain and carries no imported data, so the C# `Imported supplier 42` placeholder path was not
created (see the change-freedom rules in `CLAUDE.md`).

- [x] The restricted foreign key from an article to `suppliers.id` exists
  (`fk_article_mugs_supplier` in `V13__create_articles.sql`).
- [x] `SupplierDeleteResult.InUse` is proven end to end.
  `ArticleSupplierRelationshipIntegrationTest` installs Article **and** Supplier on one database,
  creates a mug that references a supplier, and deletes that supplier through the real admin route:
  `409 Conflict` with the stable message `Supplier is in use and cannot be deleted`, both rows
  intact, and a body that contains neither the constraint name, nor the table name, nor the
  driver's wording. The same test proves the other direction — an unreferenced supplier deletes
  normally, and the referenced one becomes deletable again once the article is gone.
- [x] Assigning a supplier and rejecting an unknown supplier id are covered by the mug write slice
  (`MugArticleAdminIntegrationTest`): the reference is the only foreign key a mug write can fail
  on, so SQL state `23503` maps to the `supplierId` field error.
