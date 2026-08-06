# Frontend - Vue 3/TypeScript SPA

## Quick Reference

Run commands from `frontend/`:

```bash
bun run dev            # Start Vite dev server
bun run build          # Type-check and build for production
bun run lint           # ESLint with auto-fix
bun run check:ui-boundary  # Scan app code for UI boundary violations
bun run format         # Format src/ with Prettier
bun run type-check     # TypeScript checking only
bun run test:unit      # Run Vitest tests
```

## Frontend Conventions

- Use Vue 3 Composition API with `<script setup lang="ts">` for SFCs.
- Keep code identifiers, comments, and technical text in English.
- Keep page, route, layout, store, and component code in the matching `views/`, `router/`, `layouts/`, `stores/`, and `components/` area folders (`admin`, `shop`, `auth`, `shared`, `ui`).
- Use vue-i18n for localized user-facing copy; the active locales are German (`de`) and English (`en`).
- Design mobile-first, then enhance with Tailwind responsive prefixes.

## UI Components

- Admin CRUD opens create/edit/delete in dialogs (`useDialogCrud` plus an `Admin<Entity>Dialog` component) instead of dedicated edit routes. Article editing and the two-tab Prompt/Price editor are the documented route-level exceptions; keep the Prompt editor route-level because its cross-tab dirty-work protection and recoverable reference/Price states are part of the workflow contract.
- Prefer existing components before creating new primitives.
- `components/ui` owns low-level primitives and generic composed UI behavior. Views, layouts, and feature components should consume those components instead of recreating generic controls locally.
- Do not use raw interactive, form, or app table primitives outside `components/ui`; this includes native `<button>`, `<input>`, `<select>`, `<textarea>`, app `<table>` structures, and interactive styled `<label>` controls.
- Do not import from `reka-ui` outside `components/ui`; wrap needed Reka behavior in a Voenix UI component first.
- Keep Voenix-specific feature components in their area folders when they represent domain or area behavior, such as admin headers, metadata panels, shop cards, badges, layouts, wizard/editor controls, or auth shells. These components should compose UI primitives internally.
- Use `cn()` from `@/lib/utils` when merging Tailwind classes.
- Keep new variants and sizes aligned with the existing component APIs.

## Routing and Auth

- Routes are split by area: `router/auth.ts`, `router/admin.ts`, and `router/shop.ts`. `router/index.ts` composes them.
- Put new routed pages under the matching `views/` subfolder.
- Admin routes require `adminGuard`; authenticated shop routes use `authGuard`; guest-only auth pages use `guestGuard`.
- Auth is cookie-based. Do not add token storage or auth localStorage.
- Session restore runs through `GET /api/auth/me` and `authReadyPromise`; the global router guard waits for it before redirect decisions.
- Treat router files as the source of truth for route inventories instead of duplicating route lists in docs.

## State Management

- Pinia stores use the composition API style.
- Shared state belongs under `stores/shared/`; admin and shop state belong under their matching area folders.
- Keep auth behavior in `stores/shared/auth.ts`.
- Magic Coins balance is handled by `stores/shop/magicCoins.ts` and fetched from `GET /api/magic-coins/balance`.
- Do not introduce stale Magic Coins caching. Concurrent balance requests should stay deduplicated.
- After login, logout, or successful image generation, refetch the Magic Coins balance so the active guest/user context is reflected.

## Testing and Verification

- Add new tests in colocated `__tests__/` directories near the code being tested.
- Run `bun run type-check` for frontend code changes.
- Run `bun run check:ui-boundary` when changing components, views, layouts, or shared UI primitives.
- Run targeted Vitest tests, or `bun run test:unit`, when changing store, router, component, or user-flow behavior.
- Run `bun run format` when editing frontend source files.
- The dev server is usually already running. Only start `bun run dev` if it is not running.
- For visual changes, verify both desktop (`1440x900`) and mobile (`375x812`) viewports with an available browser inspection tool.
- In development, the shop editor has a fixed test draft at `/editor/test`. Use this route to inspect editor layout and controls without creating a product draft or uploading an image.
