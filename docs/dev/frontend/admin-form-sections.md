# Long admin forms on one page

Most admin entities are edited in a dialog. Three editors are the exception:
the two article editors (`MugArticleEditView`, `SpodArticleEditView`) and the
prompt editor (`PromptEditView`). They are full pages, because an article or a
prompt with its price has too much to fit into a dialog. This guide explains how
such a page is laid out and how it shows problems when a save is stopped.

## Sections instead of tabs

A route-level editor shows **all** of its inputs on one page. Nothing is hidden
behind tabs. The page is split into sections, and each section is an
[`AdminFormSection`](../../../frontend/src/components/admin/shared/AdminFormSection.vue):
a card with a heading, an optional line of explanation, and the section's inputs
below.

```vue
<AdminFormSection
  id="section-details"
  title="Details"
  description="Physical mug characteristics. Required before the article can be set active."
>
  <FormField label="Height (mm)" for="article-height" :error="fieldErrors.heightMm">
    <Input id="article-height" v-model="details.heightMm" type="number" />
  </FormField>
</AdminFormSection>
```

Why no tabs? There is one *Save* button, and it saves everything. With
tabs, a user saw one tab but saved four, and a problem in a hidden tab had to be
found by switching tabs. On one page, what you see is what you save.

### The jump list beside the form

A page with four or five sections still needs orientation. The article
editors therefore put a jump list next to the form:
[`AdminFormSectionNav`](../../../frontend/src/components/admin/shared/AdminFormSectionNav.vue)
gets the sections in reading order (`{ id, label }`), renders one link per
section, and highlights the one on screen. Every `AdminFormSection` the list
points to carries the matching `id`. The list is `sticky` and shown from `lg`
up; on a phone the page is simply scrolled.

```vue
<div class="lg:grid lg:grid-cols-[minmax(0,1fr)_10rem] lg:items-start lg:gap-8">
  <form class="min-w-0 max-w-5xl space-y-4" @submit.prevent="saveArticle">
    <AdminFormSection id="section-general" title="General">…</AdminFormSection>
    …
  </form>
  <AdminFormSectionNav :sections="SECTIONS" class="hidden lg:sticky lg:top-6 lg:block" />
</div>
```

This is not a tab bar in disguise: every section stays rendered and every
section is saved, the list only scrolls. The prompt editor has two sections and
no list.

### The summary under the title

The second half of the orientation is the line under the page title. The
header (`AdminPageHeader`) has a `summary` slot, and the article editors fill it
with
[`AdminEditorSummary`](../../../frontend/src/components/admin/shared/AdminEditorSummary.vue):
the *Active* switch with a badge that says whether the article is in the shop,
a *Missing at Spreadconnect* badge where that applies, and short facts next to
them — the category, the gross price, the supplier, the last sync (the SPOD
article editor also names its product type there, for example *Men's
T-Shirt*). The facts are
computed from the form state and the last calculated price, so they follow every
edit.

The *Active* switch lives up here and not in the *General* section on purpose.
Activating is the one decision the whole page leads up to, and its preconditions
(a category, a price, an active variant, for a SPOD article also a measured
print frame) are spread over every section. In the
header, the switch and the facts that decide whether it may be flipped sit side
by side. The rule message for it (`fieldErrors.active`) is shown right under
the summary and carries `data-form-error` like any other.

### Reading order

The sections are ordered the way an operator works through the page, not the
way the data is stored. The SPOD article editor starts with the *Spreadconnect*
identity (name, descriptions, ids, sync time) — what the article *is* — then
*General*, *Price*, *Print*, and last the synced *Variants* with the size chart.
The mug editor goes *General*, *Details*, *Variants*, *Price*.

### One card per section, not cards in cards

A section is one card, and what is inside it is flat: a subsection is a
heading (`h3`, `text-base`) above a `border-t`, not another card. The price
editor used to be three cards inside the *Price* card; it is now three
subsections (*Einkauf*, *Verkauf*, *Discount*) divided by lines, each with its
amount table. Section titles are `text-xl`, descriptions `text-sm`, hints under
an input `text-xs`, so the three levels of text are told apart at a glance.

### Synced variants grouped by colour

A SPOD article's variants are read-only and many — a few colours times a few
sizes. [`AdminArticleSpodVariantGroups`](../../../frontend/src/components/admin/article/AdminArticleSpodVariantGroups.vue)
gets the article's `spodVariants` and `appearances` and shows one row per
colour (appearance) — its name, its colour swatch, and all its listing images
as thumbnails in the partner's order (read-only: the backoffice owns them) —
with the sizes as chips in garment order: a struck-through chip is a size the
partner no longer offers, the highlighted one is the default variant.
The partner's identifiers (variant id, SKU, product/appearance/size) sit behind
a *Show technical IDs* toggle and then appear under every colour in the same
columns — they are what an operator needs to find a row again in the
Spreadconnect backoffice, and nothing an operator reads otherwise.

A section that only **shows** data another system owns gets the `read-only`
prop. It then has a grey background and a *Read-only* badge, so nobody looks for
a way to edit it. The SPOD article editor has three of them:

- *Spreadconnect* shows what the backoffice owns: the name, the descriptions,
  the partner's ids, and the time of the last sync.
- *Print* shows the print frame the last sync measured from the Spreadconnect
  hotspot: the rectangle drawn on the mockup of the default variant's colour,
  with the hotspot's name and the four percentages as text beside it
  ([`AdminArticlePrintFrameViewer`](../../../frontend/src/components/admin/article/AdminArticlePrintFrameViewer.vue)).
  Nobody calibrates the frame by hand any more. When the sync could not measure
  it, the section shows an alert instead, and the backend refuses to activate the
  article — that refusal appears under the *Active* switch.
- *Variants* shows the variants grouped by colour, as described above, and the
  size chart.

A sync run writes these sections, the shop never does. What the form saves is
exactly the shop's half: `active`, `categoryId`, `subcategoryId`,
`defaultVariantId`, and the price.

The price section is the same in both article editors, so it is a component of
its own:
[`AdminArticlePriceSection`](../../../frontend/src/components/admin/pricing/AdminArticlePriceSection.vue).
The prompt editor has two sections, *Prompt* and *Price*.

The buttons sit in a bar with `sticky bottom-0`. It stays at the bottom of the
screen while the user scrolls through a long page. *Save* (or *Create* for a new
prompt) and *Cancel* are on the left; the one extra action of the editor sits on
the right — *Delete* for an article, *Copy prompt* for a prompt. On a phone that
extra button shows only its icon.

The admin layout's `<main>` has no `overflow` class on purpose. With one, `main`
would become the scroll box of the sticky bar, and because `main` itself never
scrolls, the bar would never stick.

## Showing every problem at once

When a save is stopped, the editor marks **every** problem it finds, not just
the first. The rule check of an editor therefore does not return early; it
checks all rules and answers `false` if any message was set. The price is
checked in the same round (see
[`useAdminArticleEditor.ts`](../../../frontend/src/composables/useAdminArticleEditor.ts)
and [`useAdminPromptEdit.ts`](../../../frontend/src/composables/useAdminPromptEdit.ts)).

Then the page scrolls to the first problem. This works through a marker
attribute: every place that shows an error carries `data-form-error`, and
`revealFirstFormError()` from
[`lib/formErrors.ts`](../../../frontend/src/lib/formErrors.ts) scrolls to the
first element with that attribute in the document.

| Where the error is shown | Who sets `data-form-error` |
| --- | --- |
| Below an input in a `FormField` | `FormField` itself |
| In the price editor | `AdminPriceEditor` and `AdminArticlePriceSection` |
| Anywhere else (an `Alert`, a line of text, a variant row) | the view, by hand |

When you add a new place that shows an error in one of these editors, add
`data-form-error` to it. Without it, the page cannot scroll there.
