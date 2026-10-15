# Image uploads and pasting from the clipboard

Everywhere the frontend accepts an image, the user can choose a file *or* paste
an image from the clipboard (Ctrl+V, on a Mac Cmd+V). This guide explains how
pasting works and what a new upload spot has to do to support it.

## Where images are uploaded

| Place | Component | When a paste is accepted |
| --- | --- | --- |
| Admin: article subcategory example image | `components/admin/article/subcategory/AdminArticleSubcategoryDialog.vue` | Dialog open, not saving or deleting |
| Admin: mug variant example image | `components/admin/article/AdminArticleMugVariantDialog.vue` | Dialog open, no upload running |
| Admin: prompt example image | `components/admin/prompts/AdminPromptForm.vue` | Form enabled, no upload running |
| Shop: wizard upload step | `components/shop/wizard/steps/UploadImageStep.vue` | Crop dialog closed |
| Shop: product editor, first image | `components/shop/editor/ProductDraftUpload.vue` | Always while it is shown |

## The composable

All of them call
[`useClipboardImagePaste`](../../../frontend/src/composables/useClipboardImagePaste.ts)
in their `<script setup>`:

```ts
useClipboardImagePaste(
  (file) => onExampleImageSelected([file]),
  () => open.value && !isUploadingImage.value,
)
```

The first argument receives the pasted image as a `File`. The second is a
function that says whether a paste is welcome *right now*; it is asked on every
paste, so it can read refs and props.

The composable listens for the `paste` event on the whole `document`, not on
one element. A paste goes to whatever element has focus, and in a dialog that
can be the dialog itself, a select, or a button. Listening on the document means
the user does not have to click a special field first. The listener is removed
automatically when the component unmounts (it uses `useEventListener` from
VueUse), so a closed dialog that is unmounted no longer reacts. A dialog that
stays mounted while closed must say so through its `enabled` function, which is
why the admin dialogs check `open.value`.

## What happens to a paste

1. If `enabled()` returns `false`, nothing happens.
2. The composable looks for the first file in the clipboard whose type starts
   with `image/`. Without one (the usual case: text pasted into a text field),
   it does nothing and the browser pastes as normal.
3. With an image, it calls `preventDefault()` so the browser does not also try
   to insert it somewhere.
4. Clipboard images often have a generic name such as `image.png` or none at
   all. For JPEG, PNG, WebP, and GIF the file is renamed to
   `pasted-image.<ext>`, so a check of the file extension, like the one in the
   subcategory dialog, accepts it. Other image types keep their name.
5. The file is handed to the callback. **The callback validates it exactly like
   a chosen file**: type, size, and any server-side rejection are reported the
   same way. The composable does not validate anything itself.

## Adding a new upload spot

Call `useClipboardImagePaste` with the same function the `FileInput` `@change`
handler uses, and give it an `enabled` function that is `false` whenever a new
image would be unwelcome (dialog closed, upload running, another dialog on top).
Mention pasting in the hint text next to the upload button; shop texts live in
`de.json` and `en.json`, admin texts are plain English.

Two components that are mounted at the same time and both enabled would both
receive the same paste. Keep the `enabled` functions narrow enough that this
cannot happen.
