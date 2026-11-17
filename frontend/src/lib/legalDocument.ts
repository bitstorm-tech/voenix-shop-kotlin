import DOMPurify from 'dompurify'

/**
 * iubenda's policy HTML, reduced to what the shop page shows. Images and SVGs go: iubenda's icons
 * load from iubenda.com and would hand every reader's IP to iubenda. The "manage preferences"
 * entry goes too, it needs iubenda's script, which the shop does not load. The link to iubenda's
 * hosted cookie policy points at the copy on the same page instead.
 */
export function sanitizeLegalHtml(html: string, cookiePolicyAnchor: string): string {
  const fragment = DOMPurify.sanitize(html, {
    FORBID_TAGS: ['img', 'svg', 'figure', 'style'],
    FORBID_ATTR: ['style'],
    RETURN_DOM_FRAGMENT: true,
  })
  fragment
    .querySelectorAll('svg, .iub-manage-preferences-container')
    .forEach((node) => node.remove())
  fragment
    .querySelectorAll('a.show_comp_link')
    .forEach((link) => link.setAttribute('href', `#${cookiePolicyAnchor}`))
  const container = document.createElement('div')
  container.append(fragment)
  return container.innerHTML
}
