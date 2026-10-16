<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Search } from 'lucide-vue-next'
import AdminOrdersTable from '@/components/admin/orders/AdminOrdersTable.vue'
import AdminProductionPdfLookupForm from '@/components/admin/orders/AdminProductionPdfLookupForm.vue'
import AdminProductionPdfSection from '@/components/admin/orders/AdminProductionPdfSection.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { useAdminOrdersStore } from '@/stores/admin/orders'

const ordersStore = useAdminOrdersStore()

const emailInput = ref('')
const orderIdInput = ref('')

const listError = computed(() =>
  ordersStore.listError === null
    ? null
    : ordersStore.listError.message || 'The orders could not be loaded.',
)

function filterOrders() {
  void ordersStore.fetchOrders(emailInput.value)
}

onMounted(filterOrders)

async function lookupOrder(orderId: number) {
  await ordersStore.fetchProductionPdfs(orderId)
}
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Orders" breakpoint="lg" />

    <p class="max-w-2xl text-sm text-muted-foreground">
      The 50 newest orders, or every order of one e-mail address. Open an order to read its
      timeline: payments, production, mails, its history, and its open findings.
    </p>

    <Card class="p-4">
      <div class="flex flex-col gap-3 sm:flex-row sm:items-end">
        <FormField
          class="flex-1"
          label="Customer e-mail"
          for="order-list-email"
          hint="The exact address the order was placed with; leave it empty for the newest orders."
        >
          <Input
            id="order-list-email"
            v-model="emailInput"
            type="email"
            autocomplete="off"
            placeholder="kunde@example.com"
            @keyup.enter="filterOrders"
          />
        </FormField>

        <Button class="sm:w-auto" :disabled="ordersStore.isListLoading" @click="filterOrders">
          <Search class="size-4" />
          Show orders
        </Button>
      </div>
    </Card>

    <Alert v-if="listError" variant="destructive">{{ listError }}</Alert>

    <Card
      v-else-if="ordersStore.isListLoading && ordersStore.orderList.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading orders...
    </Card>

    <Card
      v-else-if="ordersStore.orderList.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No order matches.
    </Card>

    <AdminOrdersTable v-else :orders="ordersStore.orderList" />

    <h2 class="pt-4 text-lg font-semibold">Production documents</h2>

    <p class="max-w-2xl text-sm text-muted-foreground">
      Look up the production documents of one order by its ID. An order has one PDF per involved
      supplier, and every document is generated fresh on request.
    </p>

    <AdminProductionPdfLookupForm
      v-model="orderIdInput"
      :is-loading="ordersStore.isLoading"
      @submit="lookupOrder"
    />

    <AdminProductionPdfSection />
  </section>
</template>
