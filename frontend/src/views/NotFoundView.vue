<script setup lang="ts">
import { Button } from '@/components/ui/button'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

const { t } = useI18n()

const router = useRouter()

const goHome = () => {
  router.push('/')
}

const goBack = () => {
  router.back()
}
</script>

<template>
  <div
    class="min-h-screen flex items-center justify-center bg-gradient-to-br from-background to-muted p-8"
  >
    <div class="text-center max-w-lg">
      <h1 class="text-[6rem] m-0 text-foreground font-bold leading-none">404</h1>
      <h2 class="text-3xl my-4 text-foreground">{{ t('notFound.title') }}</h2>
      <p class="text-muted-foreground text-lg mb-8">
        {{ t('notFound.text') }}
      </p>

      <div class="flex gap-4 justify-center">
        <Button variant="outline" @click="goBack">{{ t('notFound.back') }}</Button>
        <Button @click="goHome">{{ t('notFound.home') }}</Button>
      </div>
    </div>
  </div>
</template>
