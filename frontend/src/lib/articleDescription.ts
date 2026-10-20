import DOMPurify from 'dompurify'

/**
 * SPOD sends an article description as HTML ("<ul class="listMCE"><li>…"), and the sync stores it
 * as it came, cut to its column. The shop shows it as HTML, reduced to the few tags a product text
 * needs: no attributes, no links, no scripts or styles - partner text never decides what the shop
 * page does. A tag the cut left open is closed by the parser.
 */
const ALLOWED_TAGS = ['p', 'br', 'ul', 'ol', 'li', 'strong', 'b', 'em', 'i', 'u']

export function sanitizeDescriptionHtml(html: string): string {
  return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR: [] })
}

/** The description as plain text, for places with room for a line or two rather than a list. */
export function descriptionText(html: string): string {
  const fragment = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['li', 'p', 'br'],
    RETURN_DOM_FRAGMENT: true,
  })
  fragment.querySelectorAll('li, p, br').forEach((element) => element.before(' '))
  return (fragment.textContent ?? '').replace(/\s+/g, ' ').trim()
}
