<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { Webhook } from 'lucide-vue-next'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  type SpodWebhookStatus,
  useAdminProductionDestinationsStore,
} from '@/stores/admin/productionDestinations'

const props = defineProps<{ destinationId: number }>()

const destinationsStore = useAdminProductionDestinationsStore()
const statuses = ref<SpodWebhookStatus[] | null>(null)
const error = shallowRef<string | null>(null)
const isBusy = shallowRef(false)

const allActive = computed(
  () => statuses.value !== null && statuses.value.every((item) => item.state === 'ACTIVE'),
)

const STATE_LABELS: Record<SpodWebhookStatus['state'], string> = {
  ACTIVE: 'active',
  MISSING: 'missing',
  WRONG_URL: 'wrong URL',
}

function hint(item: SpodWebhookStatus): string | undefined {
  return item.otherHosts.length > 0 ? `Also subscribed: ${item.otherHosts.join(', ')}` : undefined
}

async function run(call: (id: number) => Promise<SpodWebhookStatus[]>) {
  isBusy.value = true
  error.value = null
  try {
    statuses.value = await call(props.destinationId)
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Unknown error'
  } finally {
    isBusy.value = false
  }
}

onMounted(() => run(destinationsStore.webhookStatus))
</script>

<template>
  <div
    class="flex flex-wrap items-center gap-2 text-sm"
    :data-testid="`destination-webhooks-${destinationId}`"
  >
    <span class="font-medium text-foreground">Webhooks:</span>
    <span v-if="isBusy && !statuses" class="text-muted-foreground">Checking...</span>
    <Badge
      v-for="item in statuses ?? []"
      :key="item.eventType"
      :variant="item.state === 'ACTIVE' ? 'success' : 'warning'"
      :title="hint(item)"
    >
      {{ item.eventType }}: {{ STATE_LABELS[item.state] }}
    </Badge>
    <span v-if="error" class="text-destructive">{{ error }}</span>
    <Button
      v-if="!allActive"
      type="button"
      variant="outline"
      size="sm"
      :disabled="isBusy"
      :data-testid="`destination-webhooks-register-${destinationId}`"
      @click="run(destinationsStore.registerWebhooks)"
    >
      <Webhook class="size-4" />
      {{ isBusy ? 'Working...' : 'Register webhooks' }}
    </Button>
  </div>
</template>
