<script setup lang="ts">
import { Button } from '@/components/ui/button'
import { useRouter } from 'vue-router'

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
      <h2 class="text-3xl my-4 text-foreground">Page Not Found</h2>
      <p class="text-muted-foreground text-lg mb-8">
        The page you're looking for doesn't exist or has been moved.
      </p>

      <div class="flex gap-4 justify-center">
        <Button variant="outline" @click="goBack">Go Back</Button>
        <Button @click="goHome">Go Home</Button>
      </div>
    </div>
  </div>
</template>
