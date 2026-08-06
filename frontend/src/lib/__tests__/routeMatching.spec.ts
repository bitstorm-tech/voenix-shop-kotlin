import { describe, expect, it } from 'vitest'
import { isRouteActive, isRoutePatternActive } from '../routeMatching'

describe('routeMatching', () => {
  it('matches exact paths', () => {
    expect(isRoutePatternActive('/admin/prompts', '/admin/prompts')).toBe(true)
    expect(isRoutePatternActive('/admin/prompts/categories', '/admin/prompts')).toBe(false)
  })

  it('supports simple parameter segments', () => {
    expect(isRoutePatternActive('/admin/prompts/42/edit', '/admin/prompts/:id/edit')).toBe(true)
    expect(isRoutePatternActive('/admin/prompts/42', '/admin/prompts/:id/edit')).toBe(false)
  })

  it('matches any configured active pattern', () => {
    expect(
      isRouteActive('/admin/prompts/42/edit', ['/admin/prompts', '/admin/prompts/:id/edit']),
    ).toBe(true)
  })
})
