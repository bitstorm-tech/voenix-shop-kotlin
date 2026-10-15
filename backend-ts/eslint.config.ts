import { defineConfig } from 'eslint/config'
import boundaries from 'eslint-plugin-boundaries'
import tseslint from 'typescript-eslint'

/**
 * Which module may import which, as `name: [names it may import]`. "platform" is implied for
 * every module and therefore not listed. The composition root (`src/*.ts`) and the test support
 * may import every module. See docs/dev/backend-ts/conventions/module-architecture.md.
 */
const moduleDependencies: Record<string, string[]> = {
  country: [],
  email: [],
  image: [],
  supplier: ['country'],
  pricing: [],
  spod: [],
  promotion: [],
  account: ['email'],
  article: ['image', 'pricing', 'supplier', 'spod'],
  prompt: ['image', 'pricing'],
  production: ['email', 'supplier', 'article', 'spod'],
  order: ['image', 'article', 'promotion', 'production', 'email'],
  cart: ['image', 'article', 'prompt', 'promotion', 'order'],
  checkout: ['cart', 'promotion', 'order', 'country', 'article'],
  generator: ['article', 'prompt'],
}

/** Another module is reachable through its `index.ts` only. */
const moduleIndex = (name: string) => ({
  type: 'module',
  captured: { name },
  fileInternalPath: 'index.ts',
})
const anyModuleIndex = { type: 'module', fileInternalPath: 'index.ts' }

export default defineConfig(
  { ignores: ['node_modules/**'] },
  tseslint.configs.strictTypeChecked,
  {
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-non-null-assertion': 'error',
    },
  },
  {
    files: ['src/**/*.ts', 'test-support/**/*.ts'],
    plugins: { boundaries },
    settings: {
      'import/resolver': { typescript: { project: './tsconfig.json' } },
      'boundaries/include': ['src/**/*.ts', 'test-support/**/*.ts'],
      'boundaries/elements': [
        { type: 'platform', pattern: 'src/platform' },
        { type: 'module', pattern: 'src/modules/*', capture: ['name'] },
        { type: 'test-support', pattern: 'test-support' },
        { type: 'root', pattern: 'src' },
      ],
      'boundaries/files': [{ category: 'test', pattern: '**/*.test.ts' }],
    },
    rules: {
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          policies: [
            ...Object.entries(moduleDependencies).map(([name, dependencies]) => ({
              from: { element: { type: 'module', captured: { name } } },
              allow: { to: { element: [{ type: 'platform' }, ...dependencies.map(moduleIndex)] } },
            })),
            {
              from: { element: { type: 'root' } },
              allow: { to: { element: [{ type: 'platform' }, anyModuleIndex] } },
            },
            {
              from: { element: { type: 'test-support' } },
              allow: { to: { element: [{ type: 'platform' }, anyModuleIndex] } },
            },
            {
              from: { file: { categories: 'test' } },
              allow: { to: { element: { type: 'test-support' } } },
            },
          ],
        },
      ],
    },
  },
)
