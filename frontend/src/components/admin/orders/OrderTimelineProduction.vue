<script setup lang="ts">
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { formatAdminStamp } from '@/lib/adminStamp'
import type { OrderTimeline } from '@/stores/admin/orders'

defineProps<{ production: OrderTimeline['production'] }>()

/** A stamp, or the dash of a stage that has not happened. */
const stamp = (value: string | null) => formatAdminStamp(value) ?? '—'
</script>

<template>
  <div class="space-y-3" data-testid="timeline-production">
    <Card v-if="production.request === null" class="p-4 text-sm text-muted-foreground">
      No production was requested: the order is not paid.
    </Card>

    <template v-else>
      <Card class="p-4 text-sm">
        <p class="font-medium">Production request #{{ production.request.id }}</p>
        <p class="text-muted-foreground">
          Requested {{ stamp(production.request.createdAt) }} · split
          {{ stamp(production.request.processedAt) }} · attempts
          {{ production.request.attemptCount }} · error
          {{ production.request.lastErrorCode ?? '—' }}
        </p>
      </Card>

      <p v-if="production.jobs.length === 0" class="text-sm text-muted-foreground">
        The request has not been split into jobs yet.
      </p>

      <Card
        v-for="job in production.jobs"
        :key="job.jobId"
        class="space-y-2 p-4 text-sm"
        data-testid="timeline-job"
      >
        <div class="flex flex-wrap items-center gap-2">
          <p class="font-medium">Job #{{ job.jobId }}</p>
          <Badge variant="muted">{{ job.fulfillmentChannel }}</Badge>
          <span class="text-muted-foreground">Supplier #{{ job.supplierId }}</span>
        </div>
        <p class="text-muted-foreground">
          Generated {{ stamp(job.generatedAt) }} (attempts {{ job.generationAttemptCount }}, error
          {{ job.lastGenerationErrorCode ?? '—' }}) · prepared {{ stamp(job.preparedAt) }} · shipped
          {{ stamp(job.shippedAt) }}
        </p>
        <p v-if="job.shippedAt !== null" class="text-muted-foreground">
          Shipped by
          {{ job.shippedByChannel ?? `user #${job.shippedByUserId ?? '—'}` }} · carrier
          {{ job.shippingCarrier ?? '—' }} · tracking {{ job.trackingNumber ?? '—' }}
        </p>
        <p
          v-for="delivery in job.deliveries"
          :key="delivery.deliveryId"
          class="text-muted-foreground"
          data-testid="timeline-delivery"
        >
          Delivery #{{ delivery.deliveryId }} to destination #{{ delivery.destinationId }}:
          delivered {{ stamp(delivery.deliveredAt) }} · attempts {{ delivery.attemptCount }} · error
          {{ delivery.lastErrorCode ?? '—' }}
        </p>
        <p v-if="job.spod !== null" class="text-muted-foreground" data-testid="timeline-spod">
          SPOD order {{ job.spod.externalReference ?? '(not created)' }}: create state
          {{ job.spod.createState }} (ambiguous {{ job.spod.createAmbiguousCount }}) · attempts
          {{ job.spod.attemptCount }} · error {{ job.spod.lastErrorCode ?? '—' }} · confirmed
          {{ stamp(job.spod.confirmedAt) }} · remote state {{ job.spod.remoteState ?? '—' }}
        </p>
      </Card>
    </template>
  </div>
</template>
