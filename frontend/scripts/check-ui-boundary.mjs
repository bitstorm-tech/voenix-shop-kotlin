#!/usr/bin/env node

import { readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { ElementTypes, NodeTypes, parse as parseTemplate } from '@vue/compiler-dom'
import { parse as parseSfc } from '@vue/compiler-sfc'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.resolve(scriptDir, '..')
const srcRoot = path.join(frontendRoot, 'src')
const uiRoot = path.join(srcRoot, 'components', 'ui')
const includedRoots = [
  path.join(srcRoot, 'components'),
  path.join(srcRoot, 'views'),
  path.join(srcRoot, 'layouts'),
]

const sourceExtensions = new Set([
  '.vue',
  '.ts',
  '.tsx',
  '.mts',
  '.cts',
  '.js',
  '.jsx',
  '.mjs',
  '.cjs',
])

const rawTagMessages = new Map([
  ['button', 'Use the Button component or a UI wrapper instead of raw <button>.'],
  [
    'input',
    'Use the Input, Checkbox, ColorInput, FileInput, or another UI wrapper instead of raw <input>.',
  ],
  ['select', 'Use the Select components or another UI wrapper instead of raw <select>.'],
  ['textarea', 'Use the Textarea component or another UI wrapper instead of raw <textarea>.'],
  ['table', 'Use the Table components instead of raw app table tags.'],
  ['thead', 'Use the Table components instead of raw app table tags.'],
  ['tbody', 'Use the Table components instead of raw app table tags.'],
  ['tfoot', 'Use the Table components instead of raw app table tags.'],
  ['tr', 'Use the Table components instead of raw app table tags.'],
  ['th', 'Use the Table components instead of raw app table tags.'],
  ['td', 'Use the Table components instead of raw app table tags.'],
  ['caption', 'Use the Table components instead of raw app table tags.'],
  ['colgroup', 'Use the Table components instead of raw app table tags.'],
  ['col', 'Use the Table components instead of raw app table tags.'],
])

const rawTags = new Set(rawTagMessages.keys())
const rekaImportSource = String.raw`['"]reka-ui(?:\/[^'"]*)?['"]`
const importPattern = new RegExp(
  String.raw`\bfrom\s*${rekaImportSource}|^\s*import\s*${rekaImportSource}|import\s*\(\s*${rekaImportSource}\s*\)|require\s*\(\s*${rekaImportSource}\s*\)`,
)

/** @typedef {{ file: string, line: number, column: number, message: string }} Violation */

/** @type {Violation[]} */
const violations = []
let scannedFiles = 0

function isInsidePath(filePath, ancestorPath) {
  const relativePath = path.relative(ancestorPath, filePath)
  return relativePath === '' || (!relativePath.startsWith('..') && !path.isAbsolute(relativePath))
}

function toProjectPath(filePath) {
  return path.relative(frontendRoot, filePath).split(path.sep).join('/')
}

function getFiles(dir) {
  const files = []

  if (!statSync(dir, { throwIfNoEntry: false })?.isDirectory()) {
    return files
  }

  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const entryPath = path.join(dir, entry.name)

    if (entry.isDirectory()) {
      files.push(...getFiles(entryPath))
      continue
    }

    if (entry.isFile() && sourceExtensions.has(path.extname(entry.name))) {
      files.push(entryPath)
    }
  }

  return files
}

function addViolation(filePath, line, column, message) {
  violations.push({
    file: toProjectPath(filePath),
    line,
    column,
    message,
  })
}

function scanImports(filePath, source) {
  const lines = source.split(/\r?\n/)

  lines.forEach((line, index) => {
    if (importPattern.test(line)) {
      addViolation(
        filePath,
        index + 1,
        Math.max(line.search(/reka-ui/) + 1, 1),
        'Direct reka-ui imports are only allowed inside src/components/ui.',
      )
    }
  })
}

function hasStaticAttr(node, attrName) {
  return node.props.some((prop) => prop.type === NodeTypes.ATTRIBUTE && prop.name === attrName)
}

function hasStaticOrBoundAttr(node, attrName) {
  return node.props.some((prop) => {
    if (prop.type === NodeTypes.ATTRIBUTE) {
      return prop.name === attrName
    }

    return (
      prop.type === NodeTypes.DIRECTIVE &&
      prop.name === 'bind' &&
      prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION &&
      prop.arg.content === attrName
    )
  })
}

function hasEventHandler(node) {
  return node.props.some(
    (prop) => prop.type === NodeTypes.DIRECTIVE && (prop.name === 'on' || prop.name === 'model'),
  )
}

function getStaticAttrValue(node, attrName) {
  const attr = node.props.find(
    (prop) => prop.type === NodeTypes.ATTRIBUTE && prop.name === attrName,
  )

  return attr?.value?.content
}

function getBoundStaticStringValue(node, attrName) {
  const directive = node.props.find(
    (prop) =>
      prop.type === NodeTypes.DIRECTIVE &&
      prop.name === 'bind' &&
      prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION &&
      prop.arg.content === attrName,
  )

  if (
    directive?.type !== NodeTypes.DIRECTIVE ||
    directive.exp?.type !== NodeTypes.SIMPLE_EXPRESSION
  ) {
    return undefined
  }

  const expression = directive.exp.content.trim()
  const match = expression.match(/^(['"`])([a-z]+)\1$/)
  return match?.[2]
}

function isStyledLabel(node) {
  return hasStaticOrBoundAttr(node, 'class')
}

function hasRawFormControlDescendant(node) {
  return node.children?.some((child) => {
    if (child.type !== NodeTypes.ELEMENT || child.tagType !== ElementTypes.ELEMENT) {
      return false
    }

    const tag = child.tag.toLowerCase()
    return (
      tag === 'input' ||
      tag === 'select' ||
      tag === 'textarea' ||
      hasRawFormControlDescendant(child)
    )
  })
}

function isInteractiveStyledLabel(node) {
  if (!isStyledLabel(node)) {
    return false
  }

  return (
    hasEventHandler(node) ||
    hasStaticOrBoundAttr(node, 'for') ||
    hasStaticAttr(node, 'tabindex') ||
    getStaticAttrValue(node, 'role') === 'button' ||
    hasRawFormControlDescendant(node)
  )
}

function getNativeComponentTag(node) {
  if (node.tag !== 'component') {
    return undefined
  }

  return getStaticAttrValue(node, 'is') ?? getBoundStaticStringValue(node, 'is')
}

function walkTemplateNode(filePath, node, templateStartLine) {
  if (node.type !== NodeTypes.ELEMENT) {
    for (const child of node.children ?? []) {
      walkTemplateNode(filePath, child, templateStartLine)
    }
    return
  }

  if (node.tagType === ElementTypes.ELEMENT) {
    const tag = node.tag.toLowerCase()
    const line = templateStartLine + node.loc.start.line - 1

    if (rawTags.has(tag)) {
      addViolation(filePath, line, node.loc.start.column, rawTagMessages.get(tag))
    } else if (tag === 'label' && isInteractiveStyledLabel(node)) {
      addViolation(
        filePath,
        line,
        node.loc.start.column,
        'Interactive styled <label> controls must live behind a component in src/components/ui.',
      )
    }
  }

  const nativeComponentTag = getNativeComponentTag(node)

  if (nativeComponentTag && rawTags.has(nativeComponentTag)) {
    addViolation(
      filePath,
      templateStartLine + node.loc.start.line - 1,
      node.loc.start.column,
      `Do not render raw <${nativeComponentTag}> through <component is>; use a UI wrapper instead.`,
    )
  }

  for (const child of node.children ?? []) {
    walkTemplateNode(filePath, child, templateStartLine)
  }
}

function scanVueTemplate(filePath, source) {
  const sfc = parseSfc(source, { filename: toProjectPath(filePath) })

  for (const error of sfc.errors) {
    addViolation(
      filePath,
      error.loc?.start.line ?? 1,
      error.loc?.start.column ?? 1,
      `Could not parse Vue SFC: ${error.message}`,
    )
  }

  const template = sfc.descriptor.template
  if (!template) {
    return
  }

  const templateErrors = []
  const templateAst = parseTemplate(template.content, {
    comments: false,
    onError(error) {
      templateErrors.push(error)
    },
  })

  for (const error of templateErrors) {
    addViolation(
      filePath,
      template.loc.start.line + error.loc.start.line - 1,
      error.loc.start.column,
      `Could not parse Vue template: ${error.message}`,
    )
  }

  walkTemplateNode(filePath, templateAst, template.loc.start.line)
}

for (const root of includedRoots) {
  for (const filePath of getFiles(root)) {
    if (isInsidePath(filePath, uiRoot)) {
      continue
    }

    const source = readFileSync(filePath, 'utf8')
    scannedFiles += 1
    scanImports(filePath, source)

    if (path.extname(filePath) === '.vue') {
      scanVueTemplate(filePath, source)
    }
  }
}

if (violations.length > 0) {
  console.error('UI boundary violations found:')
  for (const violation of violations) {
    console.error(`- ${violation.file}:${violation.line}:${violation.column} ${violation.message}`)
  }
  console.error('\nAllowed implementation boundary: src/components/ui/**')
  process.exit(1)
}

console.log(
  `UI boundary check passed. Scanned ${scannedFiles} files; no violations outside src/components/ui.`,
)
