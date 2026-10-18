import type { Generated, JSONColumnType } from 'kysely'
import type { ArticleType } from '@/modules/article'
import type { PaymentStatus } from './payment/mollie'

/** What has happened to an order: placed, paid, or given up because no payment could be started. */
export type OrderStatus = 'PENDING' | 'PAID' | 'CANCELLED'

/**
 * The `orders` table (V16): one placed order. Every address and amount is a snapshot of the
 * placement; `ux_orders_live_cart` keeps one non-cancelled order per cart, `ux_orders_access_token`
 * makes the token an identity, and a CHECK keeps `total = subtotal + shipping - discount`.
 */
export interface OrdersTable {
  id: Generated<number>
  cart_id: number
  guest_session_token: string | null
  user_id: number | null
  promotion_id: number | null
  access_token: string
  status: OrderStatus
  shipping_first_name: string
  shipping_last_name: string
  shipping_street: string
  shipping_house_number: string
  shipping_postal_code: string
  shipping_city: string
  shipping_country: string
  billing_first_name: string
  billing_last_name: string
  billing_street: string
  billing_house_number: string
  billing_postal_code: string
  billing_city: string
  billing_country: string
  email: string
  phone: string | null
  subtotal_cents: number
  shipping_cost_cents: number
  discount_cents: number
  total_cents: number
  created_at: Generated<Date>
  updated_at: Generated<Date>
}

/**
 * The `order_items` table (V16, V21, V31): one ordered line in the customer's order (`position`).
 * Everything from `article_type` down is a snapshot; the article and variant ids carry no foreign
 * key, so an order survives the deletion of what it bought.
 */
export interface OrderItemsTable {
  id: Generated<number>
  order_id: number
  position: number
  article_id: number
  variant_id: number
  article_type: ArticleType
  article_name: string
  variant_name: string
  supplier_article_number: string | null
  print_template_width_mm: number | null
  print_template_height_mm: number | null
  document_format_width_mm: number | null
  document_format_height_mm: number | null
  document_format_margin_bottom_mm: number | null
  spod_hotspot: string | null
  quantity: number
  price_cents: number
  prompt_price_cents: number
  prompt_id: number | null
  print_image_id: number | null
  created_at: Generated<Date>
}

/**
 * The `payments` table (V17): one attempt at collecting the money of one order. The partial unique
 * index `ux_payments_live_order` allows one payment per order whose status is not terminal.
 */
export interface PaymentsTable {
  id: Generated<number>
  order_id: number
  mollie_payment_id: string
  status: PaymentStatus
  amount_cents: number
  checkout_url: string
  created_at: Generated<Date>
  updated_at: Generated<Date>
}

/** Who moved an order: the shop itself, the customer, a signed-in admin or supplier, or a channel. */
export type OrderEventActorType = 'SYSTEM' | 'CUSTOMER' | 'ADMIN' | 'SUPPLIER' | 'CHANNEL'

/**
 * The `order_events` table (V34): the append-only history of one order, one row per transition
 * that overwrote a column or had an actor. `actor_id` is the user of an `ADMIN` or `SUPPLIER`
 * event; `details` holds bounded values only.
 */
export interface OrderEventsTable {
  id: Generated<number>
  order_id: number
  occurred_at: Generated<Date>
  kind: string
  actor_type: OrderEventActorType
  actor_id: number | null
  cause: string | null
  details: JSONColumnType<Record<string, unknown> | null, string | null, string | null>
}

declare module '@/platform/db/database' {
  interface Tables {
    orders: OrdersTable
    order_items: OrderItemsTable
    payments: PaymentsTable
    order_events: OrderEventsTable
  }
}
