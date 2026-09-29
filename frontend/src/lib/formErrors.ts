import { nextTick } from 'vue'

/**
 * Scrolls to the first message a form shows. The route-level admin editors are long pages, so the
 * problem that stopped a save may sit far away from the save button.
 *
 * Every place that shows an error carries a `data-form-error` attribute (`FormField` sets it on its
 * own message; a view sets it on an `Alert` or a line of text by hand), and the first of them in the
 * document is brought into view. It runs after the next render, because the messages it looks for
 * have only just been set.
 */
export async function revealFirstFormError() {
  await nextTick()
  // `scrollIntoView` is missing in the test DOM (jsdom), hence the optional call.
  document
    .querySelector('[data-form-error]')
    ?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
}
