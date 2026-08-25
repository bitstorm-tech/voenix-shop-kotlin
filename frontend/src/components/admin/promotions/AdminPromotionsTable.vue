<script setup lang="ts">
import { Eye, Pencil } from 'lucide-vue-next'
import { Badge } from '@/components/ui/badge'
import type { BadgeVariants } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { formatAdminStamp } from '@/lib/adminStamp'
import { formatPrice } from '@/lib/formatPrice'
import type { AdminPromotionDto } from '@/stores/admin/promotions'

interface Props {
  promotions: AdminPromotionDto[]
}

defineProps<Props>()

/** The admin surface is English-only, so number formatting is pinned to one locale. */
const ADMIN_LOCALE = 'en'

const emit = defineEmits<{
  (event: 'edit', promotion: AdminPromotionDto): void
}>()

function getStatus(promotion: AdminPromotionDto): {
  label: string
  variant: BadgeVariants['variant']
} {
  return promotion.isActive
    ? { label: 'Active', variant: 'success' }
    : { label: 'Inactive', variant: 'warning' }
}

function getLockStatus(promotion: AdminPromotionDto): {
  label: string
  variant: BadgeVariants['variant']
} {
  return promotion.isLocked
    ? { label: 'Locked', variant: 'muted' }
    : { label: 'Editable', variant: 'success' }
}

function formatDiscount(promotion: AdminPromotionDto) {
  const { discountType, discountValue } = promotion.discount
  if (discountType === 'PERCENTAGE') {
    return `${formatDecimal(discountValue)}%`
  }

  return formatPrice(discountValue)
}

function formatDecimal(value: number) {
  return new Intl.NumberFormat(ADMIN_LOCALE, { maximumFractionDigits: 2 }).format(value)
}

function getActionLabel(promotion: AdminPromotionDto) {
  return promotion.isLocked
    ? `View Promotion ${promotion.name}`
    : `Edit Promotion ${promotion.name}`
}
</script>

<template>
  <Card class="overflow-hidden">
    <div class="hidden overflow-x-auto md:block">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Promotion Code</TableHead>
            <TableHead>Discount</TableHead>
            <TableHead>Status</TableHead>
            <TableHead>Schedule</TableHead>
            <TableHead>Usage limits</TableHead>
            <TableHead>Redemptions</TableHead>
            <TableHead>Lock</TableHead>
            <TableHead class="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="promotion in promotions" :key="promotion.id">
            <TableCell class="min-w-48 text-foreground">{{ promotion.name }}</TableCell>
            <TableCell class="whitespace-nowrap font-mono text-foreground">
              {{ promotion.couponCode }}
            </TableCell>
            <TableCell class="whitespace-nowrap text-muted-foreground">
              {{ formatDiscount(promotion) }}
            </TableCell>
            <TableCell class="whitespace-nowrap">
              <Badge :variant="getStatus(promotion).variant">
                {{ getStatus(promotion).label }}
              </Badge>
            </TableCell>
            <TableCell class="min-w-52 text-sm text-muted-foreground">
              <div>
                {{ formatAdminStamp(promotion.startsAt) ?? 'No start' }}
              </div>
              <div>{{ formatAdminStamp(promotion.endsAt) ?? 'No end' }}</div>
            </TableCell>
            <TableCell class="min-w-40 text-sm text-muted-foreground">
              <div>
                Total:
                {{ promotion.usageLimitTotal ?? 'Unlimited' }}
              </div>
              <div>
                Per user:
                {{ promotion.usageLimitPerUser ?? 'Unlimited' }}
              </div>
            </TableCell>
            <TableCell class="whitespace-nowrap text-muted-foreground">
              {{ promotion.redemptionCount }}
            </TableCell>
            <TableCell class="whitespace-nowrap">
              <Badge :variant="getLockStatus(promotion).variant">
                {{ getLockStatus(promotion).label }}
              </Badge>
            </TableCell>
            <TableCell class="whitespace-nowrap text-right">
              <Button
                variant="outline"
                size="icon-sm"
                :aria-label="getActionLabel(promotion)"
                :title="getActionLabel(promotion)"
                @click="emit('edit', promotion)"
              >
                <Eye v-if="promotion.isLocked" class="size-4" />
                <Pencil v-else class="size-4" />
                <span class="sr-only">
                  {{ promotion.isLocked ? 'View' : 'Edit' }}
                </span>
              </Button>
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>

    <div class="divide-y divide-border md:hidden">
      <article
        v-for="promotion in promotions"
        :key="promotion.id"
        class="space-y-4 p-4"
        data-testid="promotion-mobile-card"
      >
        <div class="flex items-start justify-between gap-3">
          <div class="min-w-0">
            <h2 class="truncate font-medium text-foreground">{{ promotion.name }}</h2>
            <p class="mt-1 truncate font-mono text-sm text-muted-foreground">
              {{ promotion.couponCode }}
            </p>
          </div>
          <Button
            variant="outline"
            size="icon-sm"
            class="shrink-0"
            :aria-label="getActionLabel(promotion)"
            :title="getActionLabel(promotion)"
            @click="emit('edit', promotion)"
          >
            <Eye v-if="promotion.isLocked" class="size-4" />
            <Pencil v-else class="size-4" />
          </Button>
        </div>

        <div class="flex flex-wrap gap-2">
          <Badge :variant="getStatus(promotion).variant">
            {{ getStatus(promotion).label }}
          </Badge>
          <Badge :variant="getLockStatus(promotion).variant">
            {{ getLockStatus(promotion).label }}
          </Badge>
        </div>

        <dl class="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
          <div>
            <dt class="text-muted-foreground">Discount</dt>
            <dd class="mt-0.5 font-medium text-foreground">{{ formatDiscount(promotion) }}</dd>
          </div>
          <div>
            <dt class="text-muted-foreground">Redemptions</dt>
            <dd class="mt-0.5 font-medium text-foreground">{{ promotion.redemptionCount }}</dd>
          </div>
          <div class="col-span-2">
            <dt class="text-muted-foreground">Schedule</dt>
            <dd class="mt-0.5 text-foreground">
              {{ formatAdminStamp(promotion.startsAt) ?? 'No start' }}
              <span aria-hidden="true">–</span>
              {{ formatAdminStamp(promotion.endsAt) ?? 'No end' }}
            </dd>
          </div>
          <div class="col-span-2">
            <dt class="text-muted-foreground">Usage limits</dt>
            <dd class="mt-0.5 text-foreground">
              Total:
              {{ promotion.usageLimitTotal ?? 'Unlimited' }}
              <span aria-hidden="true">·</span>
              Per user:
              {{ promotion.usageLimitPerUser ?? 'Unlimited' }}
            </dd>
          </div>
        </dl>
      </article>
    </div>
  </Card>
</template>
