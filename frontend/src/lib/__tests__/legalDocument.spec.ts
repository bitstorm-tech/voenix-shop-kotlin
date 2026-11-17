import { describe, expect, it } from 'vitest'
import { sanitizeLegalHtml } from '@/lib/legalDocument'

describe('sanitizeLegalHtml', () => {
  it('drops everything that would load from iubenda or run code', () => {
    const html =
      '<div><figure><img src="https://www.iubenda.com/icon.svg" onerror="steal()"></figure><h4>Sliplane</h4>' +
      '<p class="iubenda-branding">Erklärung<svg><path d="M0"/></svg></p><script>steal()</script>' +
      '<p style="color:red" onclick="steal()">Text</p></div>'

    expect(sanitizeLegalHtml(html, 'cookies')).toBe(
      '<div><h4>Sliplane</h4><p class="iubenda-branding">Erklärung</p><p>Text</p></div>',
    )
  })

  it('removes the preferences entry and keeps the request mail links', () => {
    const html =
      '<ul><li><a href="mailto:write-us@voenix.shop?subject=Recht%20auf%20Zugang" target="_top">Zugang</a></li>' +
      '<li class="iub-manage-preferences-container" style="visibility: hidden;"><a href="#">Einstellungen</a></li></ul>'

    expect(sanitizeLegalHtml(html, 'cookies')).toBe(
      '<ul><li><a href="mailto:write-us@voenix.shop?subject=Recht%20auf%20Zugang">Zugang</a></li></ul>',
    )
  })

  it('points the cookie policy link at the copy on the page', () => {
    const html =
      '<a href="//www.iubenda.com/app/private/privacy-policy/1/cookie-policy" class="show_comp_link">Cookie-Richtlinie</a>'

    expect(sanitizeLegalHtml(html, 'cookie-policy-document')).toBe(
      '<a href="#cookie-policy-document" class="show_comp_link">Cookie-Richtlinie</a>',
    )
  })
})
