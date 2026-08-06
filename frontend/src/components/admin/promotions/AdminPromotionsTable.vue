<script setup lang="ts">
import { Eye, Pencil } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
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
import { formatPrice } from '@/lib/formatPrice'
import type { AdminPromotionDto } from '@/stores/admin/promotions'

interface Props {
  promotions: AdminPromotionDto[]
}

defineProps<Props>()
const { locale, t } = useI18n()

const emit = defineEmits<{
  (event: 'edit', promotion: AdminPromotionDto): void
}>()

function getStatus(promotion: AdminPromotionDto): {
  label: string
  variant: BadgeVariants['variant']
} {
  return promotion.isActive
    ? { label: t('admin.promotions.table.active'), variant: 'success' }
    : { label: t('admin.promotions.table.inactive'), variant: 'warning' }
}

function getLockStatus(promotion: AdminPromotionDto): {
  label: string
  variant: BadgeVariants['variant']
} {
  return promotion.isLocked
    ? { label: t('admin.promotions.table.locked'), variant: 'muted' }
    : { label: t('admin.promotions.table.editable'), variant: 'success' }
}

function formatDiscount(promotion: AdminPromotionDto) {
  if (promotion.discountType === 'PERCENTAGE') {
    return `${formatDecimal(promotion.discountValue)}%`
  }

  return formatPrice(promotion.discountValue)
}

function formatDecimal(value: number) {
  return new Intl.NumberFormat(locale.value, { maximumFractionDigits: 2 }).format(value)
}

function formatDate(value: string | null) {
  if (!value) {
    return null
  }

  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(locale.value, {
        dateStyle: 'medium',
        timeStyle: 'short',
      }).format(date)
}

function getActionLabel(promotion: AdminPromotionDto) {
  return t(
    promotion.isLocked
      ? 'admin.promotions.table.viewPromotion'
      : 'admin.promotions.table.editPromotion',
    { name: promotion.name },
  )
}
</script>

<template>
  <Card class="overflow-hidden">
    <div class="hidden overflow-x-auto md:block">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{{ t('admin.promotions.table.name') }}</TableHead>
            <TableHead>{{ t('admin.promotions.table.code') }}</TableHead>
            <TableHead>{{ t('admin.promotions.table.discount') }}</TableHead>
            <TableHead>{{ t('admin.promotions.table.status') }}</TableHead>
            <TableHead>{{ t('admin.promotions.table.schedule') }}</TableHead>
            <TableHead>{{ t('admin.promotions.table.usageLimits') }}</TableHead>
            <TableHead>{{ t('admin.promotions.table.redemptions') }}</TableHead>
            <TableHead>{{ t('admin.promotions.table.lock') }}</TableHead>
            <TableHead class="text-right">{{ t('admin.promotions.table.actions') }}</TableHead>
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
                {{ formatDate(promotion.startsAt) ?? t('admin.promotions.table.noStart') }}
              </div>
              <div>{{ formatDate(promotion.endsAt) ?? t('admin.promotions.table.noEnd') }}</div>
            </TableCell>
            <TableCell class="min-w-40 text-sm text-muted-foreground">
              <div>
                {{ t('admin.promotions.table.total') }}:
                {{ promotion.usageLimitTotal ?? t('admin.promotions.table.unlimited') }}
              </div>
              <div>
                {{ t('admin.promotions.table.perUser') }}:
                {{ promotion.usageLimitPerUser ?? t('admin.promotions.table.unlimited') }}
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
                  {{
                    t(
                      promotion.isLocked
                        ? 'admin.promotions.table.view'
                        : 'admin.promotions.table.edit',
                    )
                  }}
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
            <dt class="text-muted-foreground">{{ t('admin.promotions.table.discount') }}</dt>
            <dd class="mt-0.5 font-medium text-foreground">{{ formatDiscount(promotion) }}</dd>
          </div>
          <div>
            <dt class="text-muted-foreground">{{ t('admin.promotions.table.redemptions') }}</dt>
            <dd class="mt-0.5 font-medium text-foreground">{{ promotion.redemptionCount }}</dd>
          </div>
          <div class="col-span-2">
            <dt class="text-muted-foreground">{{ t('admin.promotions.table.schedule') }}</dt>
            <dd class="mt-0.5 text-foreground">
              {{ formatDate(promotion.startsAt) ?? t('admin.promotions.table.noStart') }}
              <span aria-hidden="true">–</span>
              {{ formatDate(promotion.endsAt) ?? t('admin.promotions.table.noEnd') }}
            </dd>
          </div>
          <div class="col-span-2">
            <dt class="text-muted-foreground">{{ t('admin.promotions.table.usageLimits') }}</dt>
            <dd class="mt-0.5 text-foreground">
              {{ t('admin.promotions.table.total') }}:
              {{ promotion.usageLimitTotal ?? t('admin.promotions.table.unlimited') }}
              <span aria-hidden="true">·</span>
              {{ t('admin.promotions.table.perUser') }}:
              {{ promotion.usageLimitPerUser ?? t('admin.promotions.table.unlimited') }}
            </dd>
          </div>
        </dl>
      </article>
    </div>
  </Card>
</template>
