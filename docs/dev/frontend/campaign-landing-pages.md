# Campaign landing pages

Campaign (or "niche") landing pages are marketing pages for one specific audience. Social media
posts link to them, and their only job is to move the visitor into the wizard funnel. They are
plain shop views: a Vue component under `frontend/src/views/shop/`, a route in
`frontend/src/router/shop.ts`, and copy in the `de`/`en` locale files. There is no CMS.

## The Royal Dog page (`/royal-dog`)

The first campaign page sells the "make your dog royalty" story — kings and queens alike: upload
a dog photo, the AI paints a royal portrait, the portrait lands on a mug.

- View: `frontend/src/views/shop/RoyalDogView.vue`
- Section components: `frontend/src/components/shop/landing/royaldog/`
- Copy: the `royalDog` namespace in `frontend/src/i18n/locales/de.json` and `en.json`

The page has five sections: a hero with a before/after comparison slider in a gilded frame, a
three-step "coronation" explainer, a gallery of finished portraits, reviews, and a closing call to
action. Both call-to-action buttons open the wizard.

### Wiring the campaign prompt

The royal-portrait style is a normal admin-managed prompt (created under `/admin/prompts`). The
page stores its id in one constant in `RoyalDogView.vue`:

```ts
const ROYAL_DOG_PROMPT_ID: number | null = null
```

While the constant is `null`, the buttons link to `/wizard?start=upload` and the visitor picks a
style themselves. Once the prompt exists, set the constant to its id; the buttons then link to
`/wizard?start=upload&promptId=<id>`.

### Campaign images

Drop campaign renders into `frontend/src/assets/landing/royal-dog/`. The view picks them up by
file name at build time, so no code changes are needed:

- `before.jpg` (or `.png`/`.webp`/`.jpeg`) — the original dog photo for the hero slider
- `after.jpg` — the matching royal portrait for the hero slider
- any other image — shown in the "freshly crowned" gallery, sorted by file name

Missing files degrade gracefully: without a `before`/`after` pair the hero shows a styled
placeholder panel, and with no gallery images the gallery section stays hidden.

## The header logo goes back to the campaign page

A visitor who enters through a campaign page should stay in that funnel: clicking the Voenix.Shop
logo in the header brings them back to the campaign page, not to the default shop landing page.

This works through a route flag and a small Pinia store:

- Campaign routes carry `meta: { campaignLanding: true }` in `frontend/src/router/shop.ts`.
- A router hook in `frontend/src/router/index.ts` sees that flag after each navigation and stores
  the page's path in the campaign store (`frontend/src/stores/shop/campaign.ts`).
- The header logo (`frontend/src/components/shop/Header.vue`) links to the stored path, which
  defaults to `/` for everyone else.

The path is kept in `sessionStorage`, so a page reload keeps the campaign home while a new tab
starts fresh. New campaign pages get this behavior by setting the meta flag — nothing else.

## Wizard deep links

The wizard (`/wizard`) understands two query parameters, and campaign pages combine them:

- `promptId=<id>` preselects a style. A stale or unknown id is ignored.
- `start=upload` puts the photo upload first instead of the style choice.

With both parameters and a valid prompt id, the style step is removed entirely: the visitor
uploads, picks a mug, and generates. This is the shortest path from a social media click to a
generated preview, which is why campaign links should always carry both parameters.

## Adding another campaign page

1. Create the view under `frontend/src/views/shop/` and its section components under
   `frontend/src/components/shop/landing/<campaign>/`.
2. Register the route in `frontend/src/router/shop.ts` inside the `ShopLayout` children, with a
   `meta.title`.
3. Add the copy namespace to both `de.json` and `en.json`.
4. Link every call to action to `/wizard?start=upload&promptId=<id>`.

Keep the page focused on one action. The Royal Dog page is the template: one promise, one visual
proof, one button.
