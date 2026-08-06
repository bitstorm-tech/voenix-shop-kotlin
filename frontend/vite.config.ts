import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          isCustomElement: (tag) => tag === 'model-viewer',
        },
      },
    }),
    tailwindcss(),
    VitePWA({
      registerType: 'prompt',
      manifest: {
        name: 'Voenix Shop',
        short_name: 'Voenix',
        start_url: '/',
        display: 'standalone',
        background_color: '#ffffff',
        theme_color: '#ffffff',
        icons: [
          {
            src: '/favicon.svg',
            sizes: 'any',
            type: 'image/svg+xml',
          },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,woff,woff2}'],
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//, /^\/images\//],
        runtimeCaching: [
          {
            urlPattern: /\/api\//,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'api-cache',
              expiration: { maxEntries: 100, maxAgeSeconds: 60 * 60 },
            },
          },
          {
            urlPattern: /\/images\//,
            handler: 'CacheFirst',
            options: {
              cacheName: 'image-cache',
              expiration: { maxEntries: 200, maxAgeSeconds: 30 * 24 * 60 * 60 },
            },
          },
          {
            urlPattern: /^https:\/\/fonts\.(googleapis|gstatic)\.com\//,
            handler: 'CacheFirst',
            options: {
              cacheName: 'font-cache',
              expiration: { maxEntries: 20, maxAgeSeconds: 365 * 24 * 60 * 60 },
            },
          },
        ],
      },
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/images': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            // Vendor: 3D libraries (model-viewer, three.js, lit)
            {
              name: 'vendor-3d',
              test: /node_modules[\\/](@google[\\/]model-viewer|three|lit|lit-html|lit-element|@lit[\\/]|@lit-labs[\\/]|@monogrid[\\/])/,
              priority: 30,
            },
            // Vendor: Vue ecosystem
            {
              name: 'vendor-vue',
              test: /node_modules[\\/](vue|pinia|@vue[\\/]|radix-vue|reka-ui)/,
              priority: 20,
            },
            // Vendor: everything else in node_modules
            {
              name: 'vendor',
              test: /node_modules/,
              priority: 10,
            },
            // App: admin code
            {
              name: 'admin',
              test: /[\\/](views|components|stores)[\\/]admin[\\/]|[\\/]layouts[\\/]AdminLayout\.vue|[\\/]router[\\/]admin\.ts/,
              priority: 5,
            },
            // App: shop code
            {
              name: 'shop',
              test: /[\\/](views|components|stores)[\\/]shop[\\/]|[\\/]layouts[\\/]ShopLayout\.vue|[\\/]router[\\/]shop\.ts/,
              priority: 5,
            },
            // App: shared code
            {
              name: 'shared',
              test: /[\\/]components[\\/]shared[\\/]|[\\/]stores[\\/]shared[\\/]|[\\/]layouts[\\/]EmptyLayout\.vue|[\\/]router[\\/]guards\.ts/,
              priority: 5,
            },
          ],
        },
        // Dynamic chunk naming for route-based code splitting
        chunkFileNames: (chunkInfo) => {
          const facadeModuleId = chunkInfo.facadeModuleId || ''

          // Admin views go into admin folder
          if (facadeModuleId.includes('/views/admin/')) {
            return 'assets/admin/[name]-[hash].js'
          }
          // Shop views go into shop folder
          if (facadeModuleId.includes('/views/shop/')) {
            return 'assets/shop/[name]-[hash].js'
          }
          // Auth views
          if (facadeModuleId.includes('/views/auth/')) {
            return 'assets/auth/[name]-[hash].js'
          }
          // Everything else
          return 'assets/[name]-[hash].js'
        },
      },
    },
    // Enable source maps for debugging
    sourcemap: true,
    // Optimize chunk size
    chunkSizeWarningLimit: 1000,
  },
})
