import { describe, expect, it } from 'vitest'
import { descriptionText, sanitizeDescriptionHtml } from '@/lib/articleDescription'

const SPOD_DESCRIPTION =
  'Kombistark - so punktet dieses Shirt. <ul class="listMCE"><li> Gerade geschnitten </li><li> Material: 100% Baumwolle </li></ul>'

describe('sanitizeDescriptionHtml', () => {
  it('keeps the list and drops its attributes', () => {
    expect(sanitizeDescriptionHtml(SPOD_DESCRIPTION)).toBe(
      'Kombistark - so punktet dieses Shirt. <ul><li> Gerade geschnitten </li><li> Material: 100% Baumwolle </li></ul>',
    )
  })

  it('removes scripts, handlers, links, and styles', () => {
    const html =
      '<p onclick="steal()">Text</p><script>steal()</script><a href="javascript:steal()">Link</a><img src=x onerror="steal()"><style>body{}</style>'

    expect(sanitizeDescriptionHtml(html)).toBe('<p>Text</p>Link')
  })

  it('closes a tag the column cut left open', () => {
    expect(sanitizeDescriptionHtml('<ul><li>Gerade geschn')).toBe('<ul><li>Gerade geschn</li></ul>')
  })
})

describe('descriptionText', () => {
  it('reads the list items as words, not glued together', () => {
    expect(descriptionText(SPOD_DESCRIPTION)).toBe(
      'Kombistark - so punktet dieses Shirt. Gerade geschnitten Material: 100% Baumwolle',
    )
  })

  it('decodes entities and drops a cut-off tag', () => {
    expect(descriptionText('Rock &amp; Roll <ul cla')).toBe('Rock & Roll')
  })
})
