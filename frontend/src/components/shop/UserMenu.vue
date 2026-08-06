<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Package, User, LogOut } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/shared/auth'
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'

const authStore = useAuthStore()
const router = useRouter()
const { t } = useI18n()

const menuValue = ref('')

watch(menuValue, (value) => {
  if (value === 'profile') {
    router.push('/profile')
  } else if (value === 'orders') {
    router.push('/orders')
  } else if (value === 'logout') {
    authStore.logout()
    router.push('/')
  }
  // Reset selection after action
  menuValue.value = ''
})
</script>

<template>
  <Select v-model="menuValue">
    <SelectTrigger
      class="h-auto w-auto gap-2 border-0 bg-transparent px-2 py-1.5 shadow-none focus:ring-0"
    >
      <User class="size-4" />
      <span class="text-sm">{{
        authStore.user?.shippingAddress?.firstName || authStore.user?.email
      }}</span>
    </SelectTrigger>
    <SelectContent align="end">
      <SelectItem value="orders">
        <span class="flex items-center">
          <Package class="mr-2 size-4" />
          {{ t('mobileMenu.orders') }}
        </span>
      </SelectItem>
      <SelectItem value="profile">
        <span class="flex items-center">
          <User class="mr-2 size-4" />
          {{ t('mobileMenu.profile') }}
        </span>
      </SelectItem>
      <SelectItem value="logout">
        <span class="flex items-center">
          <LogOut class="mr-2 size-4" />
          {{ t('mobileMenu.logout') }}
        </span>
      </SelectItem>
    </SelectContent>
  </Select>
</template>
