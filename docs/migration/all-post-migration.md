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
  `PromotionService`, `ProductionDestinationService`, four Article services
  (`MugArticleService`, `PublicMugService`, `ArticleCategoryService`,
  `ArticleSubcategoryService`), and five Prompt services (`PromptService`,
  `PromptCategoryService`, `PromptSubcategoryService`, `PromptSlotService`,
  `PromptSlotVariantService`) — the Prompt migration of 2026-07-28 added the
  eighth module without changing the decision;
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

## MagicCoins guest-balance claim (open decision for Joe)

The legacy backend never claims MagicCoins guest balances on login or
registration — a known gap the Account migration handed to Cart. The Cart
council (2026-07-29) deliberately deferred it again: closing the gap is a
behavior *extension*, and it first needs a rule for the case where a guest
balance and a user balance both exist (adding is abusable, discarding can
destroy bought or earned balance) plus protection against repeated claims of
the same token.

- [ ] Joe decides the merge rule; whichever migration or feature implements
  it extends the account module's `GuestDataClaims` port with a MagicCoins
  branch (origin: [`cart-migration.md`](cart-migration.md), decision log
  2026-07-29).

## Guest token lifetime across login and logout (open decision for Joe)

The `voenix.guest` cookie is minted once per browser and then never touched
again by the authentication flow: it is not rotated on login, and `AccountRoutes`
clears only the `UserSession` on logout. So after signing out, the claimed cart
and its print images stay reachable for the cookie's remaining 30 days — the
guest half of the ownership check still matches. On a shared browser the next
person can see them.

This is **not** a defect of the Cart migration. Legacy behaves exactly this way,
and deviation 14 approved the legacy adoption semantics as a whole; the cart is
simply the first migrated surface with real customer content behind that token.
It is recorded here because the answer is cross-cutting: it belongs to the guest
token in `platform` and the session lifecycle in `account`, not to any single
module.

- [ ] Joe decides whether the guest token is rotated on login and/or cleared on
  logout. Rotating on login costs nothing once the claim has run (the rows
  already carry the user id); clearing on logout ends anonymous continuity of
  the same browser, which is a product question, not a technical one. Origin:
  [`cart-migration.md`](cart-migration.md), phase-3 verification 2026-07-30.
