# All-migrations post-migration to-do list

This list collects work that is intentionally deferred until **the complete
.NET-to-Kotlin migration is finished** — points that span several modules or
only make sense once no further migration can change the picture. Module- or
feature-specific deferred work stays in the per-module
`<module>-post-migration.md` files; this file holds only the cross-cutting
items.

When a migration retrospective produces a finding whose action must wait for
the end of the whole migration, record the finding in the module record as
usual and move the *action* here with a pointer back to its origin.

## Shared `databaseOperation` helper (open decision for Joe)

The service-level "catch `SQLException`, rethrow `CancellationException`, log,
return `UnexpectedFailure`" helper is copied per module instead of living in
`platform`:

- byte-identical `databaseOperation` in `VatService`, `SupplierService`,
  `PromotionService`, `ProductionDestinationService`, and four Article services
  (`MugArticleService`, `PublicMugService`, `ArticleCategoryService`,
  `ArticleSubcategoryService`);
- the same shape under other names in `PriceService`
  (`withUnexpectedFailureHandling`) and `MagicCoinsService`
  (`withFailureFallback`);
- `CountryService` and `AccountService` inline the same `try`/`catch` per
  operation.

History: the Promotion retrospective recorded the duplication and Joe declined
the move on 2026-07-26, naming "a seventh copy" as the trigger to revisit. The
Article migration (T10, 2026-07-28) is that seventh module and reopened the
question; see the retrospective table in
[`article-migration.md`](article-migration.md).

Proposal, unchanged: put the helper next to `OperationResult` in `platform`
(for example `suspend fun <T> Logger.databaseOperation(message) { ... }`) and
delete the copies. Known constraint: `AccountService` returns module-specific
result types (`RegisterResult`, `LoginResult`, `ChangeEmailResult`) whose own
`UnexpectedFailure` variants an `OperationResult`-shaped helper cannot
produce, so a shared version must be generic in the result type or will only
serve part of the repository.

- [ ] Joe decides whether the helper moves to `platform`; if yes, migrate all
  copies in one sweep after the last module migration, so no migration has to
  chase a moving architecture default.
