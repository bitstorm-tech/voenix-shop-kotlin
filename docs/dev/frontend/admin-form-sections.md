# Long admin forms on one page

Most admin entities are edited in a dialog. The two article editors
(`MugArticleEditView`, `TshirtArticleEditView`) are the exception: they are full
pages, because an article has too much to fit into a dialog. This guide explains
how such a page is laid out and how it shows problems when a save is stopped.

## Sections instead of tabs

An article editor shows **all** of its inputs on one page. Nothing is hidden
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

The price section is the same in both editors, so it is a component of its own:
[`AdminArticlePriceSection`](../../../frontend/src/components/admin/pricing/AdminArticlePriceSection.vue).

The buttons (*Save*, *Cancel*, *Delete*) sit in a bar with `sticky bottom-0`. It
stays at the bottom of the screen while the user scrolls through a long page.

## Showing every problem at once

When a save is stopped, the editor marks **every** problem it finds, not just
the first. The `validate()` function of an editor therefore does not return
early; it checks all rules and answers `false` if any message was set. The price
is checked in the same round (see
[`useAdminArticleEditor.ts`](../../../frontend/src/composables/useAdminArticleEditor.ts)).

Then the page scrolls to the first problem. This works through a marker
attribute: every place that shows an error carries `data-form-error`, and the
editor scrolls to the first element with that attribute in the document.

| Where the error is shown | Who sets `data-form-error` |
| --- | --- |
| Below an input in a `FormField` | `FormField` itself |
| In the price editor | `AdminPriceEditor` and `AdminArticlePriceSection` |
| Anywhere else (an `Alert`, a line of text, a variant row) | the view, by hand |

When you add a new place that shows an error in an article editor, add
`data-form-error` to it. Without it, the page cannot scroll there.
