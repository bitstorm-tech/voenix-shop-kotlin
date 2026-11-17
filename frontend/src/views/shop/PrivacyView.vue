<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchJson } from '@/lib/api'
import { sanitizeLegalHtml } from '@/lib/legalDocument'

interface PrivacyDocuments {
  privacyPolicy: string
  cookiePolicy: string
}

const COOKIE_POLICY_ANCHOR = 'cookie-policy-document'

const { t } = useI18n()
const documents = ref<PrivacyDocuments | null>(null)
const failed = ref(false)

onMounted(async () => {
  try {
    const answer = await fetchJson<PrivacyDocuments>('/api/legal/privacy')
    documents.value = {
      privacyPolicy: sanitizeLegalHtml(answer.privacyPolicy, COOKIE_POLICY_ANCHOR),
      cookiePolicy: sanitizeLegalHtml(answer.cookiePolicy, COOKIE_POLICY_ANCHOR),
    }
  } catch {
    failed.value = true
  }
})
</script>

<template>
  <section class="bg-background text-foreground">
    <div class="legal-document mx-auto max-w-3xl px-6 py-14 sm:px-8 lg:py-20">
      <template v-if="documents">
        <!-- eslint-disable-next-line vue/no-v-html -- sanitized iubenda text, see legalDocument.ts -->
        <article v-html="documents.privacyPolicy" />
        <!-- eslint-disable-next-line vue/no-v-html -- sanitized iubenda text, see legalDocument.ts -->
        <article :id="COOKIE_POLICY_ANCHOR" class="mt-16" v-html="documents.cookiePolicy" />
      </template>
      <template v-else>
        <h1>{{ t('footerPages.privacy.title') }}</h1>
        <p role="status">
          {{ failed ? t('footerPages.privacy.loadError') : t('footerPages.privacy.loading') }}
        </p>
      </template>
    </div>
  </section>
</template>

<style scoped>
.legal-document :deep(h1) {
  margin-bottom: 1rem;
  font-size: 1.75rem;
  font-weight: 700;
  line-height: 1.15;
  overflow-wrap: anywhere;
  hyphens: auto;
}
@media (min-width: 640px) {
  .legal-document :deep(h1) {
    font-size: 2.25rem;
  }
}
.legal-document :deep(h2) {
  margin: 2.5rem 0 0.75rem;
  font-size: 1.5rem;
  font-weight: 700;
  scroll-margin-top: 6rem;
}
.legal-document :deep(h3) {
  margin: 1.75rem 0 0.5rem;
  font-size: 1.15rem;
  font-weight: 700;
}
.legal-document :deep(h4),
.legal-document :deep(h5) {
  margin: 1.25rem 0 0.25rem;
  font-weight: 700;
}
.legal-document :deep(p),
.legal-document :deep(li) {
  color: var(--foreground-soft);
  line-height: 1.75;
}
.legal-document :deep(p) {
  margin: 0.5rem 0;
}
.legal-document :deep(ul) {
  margin: 0.5rem 0;
  padding-left: 1.25rem;
  list-style: disc;
}
.legal-document :deep(a) {
  color: var(--primary);
  text-decoration: underline;
  overflow-wrap: anywhere;
}
.legal-document :deep(hr) {
  margin: 2rem 0;
  border-color: var(--border);
}
.legal-document :deep(section),
.legal-document :deep(article) {
  scroll-margin-top: 6rem;
}
</style>
