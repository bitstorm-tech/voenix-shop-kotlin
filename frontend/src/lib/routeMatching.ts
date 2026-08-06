function normalizePath(path: string): string {
  const withoutHash = path.split('#')[0] ?? ''
  const withoutQuery = withoutHash.split('?')[0] ?? ''
  const normalized = withoutQuery.replace(/\/+$/, '')

  return normalized === '' ? '/' : normalized
}

function getPathSegments(path: string): string[] {
  const normalized = normalizePath(path)

  if (normalized === '/') {
    return []
  }

  return normalized.split('/').filter(Boolean)
}

export function isRoutePatternActive(currentPath: string, pattern: string): boolean {
  const currentSegments = getPathSegments(currentPath)
  const patternSegments = getPathSegments(pattern)

  if (currentSegments.length !== patternSegments.length) {
    return false
  }

  return patternSegments.every((patternSegment, index) => {
    if (patternSegment.startsWith(':')) {
      return currentSegments[index] !== undefined && currentSegments[index] !== ''
    }

    return currentSegments[index] === patternSegment
  })
}

export function isRouteActive(currentPath: string, patterns: string[]): boolean {
  return patterns.some((pattern) => isRoutePatternActive(currentPath, pattern))
}
