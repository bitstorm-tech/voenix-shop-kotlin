<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import ContentPageLayout from '@/components/shop/ContentPageLayout.vue'

const { t } = useI18n()
</script>

<template>
  <ContentPageLayout
    :eyebrow="t('footerPages.sectionEyebrow')"
    :title="t('footerPages.contact.title')"
    :description="t('footerPages.contact.description')"
    :note="t('footerPages.contentNote')"
    :primary-action-label="t('footerPages.backHome')"
    :secondary-action-label="t('footerPages.browseProducts')"
  />
</template>
