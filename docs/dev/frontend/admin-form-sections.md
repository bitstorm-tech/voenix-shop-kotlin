# Long admin forms on one page

Most admin entities are edited in a dialog. Three editors are the exception:
the two article editors (`MugArticleEditView`, `TshirtArticleEditView`) and the
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

A section that only **shows** data another system owns gets the `read-only`
prop. It then has a grey background and a *Read-only* badge, so nobody looks for
a way to edit it. The *Spreadconnect* section of the t-shirt editor is the
example: a sync run writes it, the shop never does.

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
