import type { Generated } from 'kysely'

/**
 * The tables the production module owns (V6–V9, reshaped by V22–V25). See the production package
 * guide for what each one is for; the comments here name only what the types cannot say.
 */

/** A supplier's way of being reached; the channel's own columns live in one detail table each. */
export interface ProductionDestinationsTable {
  id: Generated<number>
  supplier_id: number
  /** `SFTP` or `SPOD`, fixed at creation. */
  channel: string
  label: string
  enabled: Generated<boolean>
  notification_email: string | null
  notification_name: string | null
  created_at: Generated<Date>
  updated_at: Generated<Date>
}

export interface ProductionDestinationSftpTable {
  id: number
  channel: Generated<string>
  host: string
  port: Generated<number>
  username: string
  /** Write-only: selected by the delivery read alone. */
  password: string
  host_key_fingerprint: string
  remote_path: Generated<string>
  timeout_seconds: number
}

export interface ProductionDestinationSpodTable {
  id: number
  channel: Generated<string>
  environment: string
  /** Write-only: selected by the submission and the sync reads alone. */
  access_token: string
  timeout_seconds: number
}

/** One row per paid order; open while `processed_at` is `NULL`. */
export interface ProductionRequestsTable {
  id: Generated<number>
  order_id: number
  attempt_count: Generated<number>
  last_error_code: string | null
  created_at: Generated<Date>
  processed_at: Date | null
}

/** One row per request and supplier. */
export interface ProductionJobsTable {
  id: Generated<number>
  request_id: number
  supplier_id: number
  fulfillment_channel: string
  file_name: string
  content_sha256: string | null
  generation_attempt_count: Generated<number>
  last_generation_error_code: string | null
  created_at: Generated<Date>
  generated_at: Date | null
  prepared_at: Date | null
  shipped_at: Date | null
  shipped_by_user_id: number | null
  shipped_by_channel: string | null
  shipping_carrier: string | null
  shipping_carrier_reported: string | null
  tracking_number: string | null
}

/** The item lines a job was split with, written once in the split transaction. */
export interface ProductionJobItemsTable {
  production_job_id: number
  position: number
  article_name: string
  variant_name: string
  supplier_article_number: string | null
  quantity: number
}

/** One row per SFTP job and enabled destination; open while `delivered_at` is `NULL`. */
export interface ProductionDeliveriesTable {
  id: Generated<number>
  production_job_id: number
  destination_id: number
  attempt_count: Generated<number>
  last_error_code: string | null
  created_at: Generated<Date>
  delivered_at: Date | null
}

/** The remote order of one SPOD job. */
export interface ProductionSpodOrdersTable {
  production_job_id: number
  /** The partner's order id: the only handle that order will ever have. */
  external_reference: string | null
  create_state: Generated<string>
  create_ambiguous_count: Generated<number>
  attempt_count: Generated<number>
  last_error_code: string | null
  created_at: Generated<Date>
  confirmed_at: Date | null
  remote_state: string | null
}

/** The design uploaded for one item position of a SPOD job. */
export interface ProductionSpodDesignsTable {
  production_job_id: number
  position: number
  design_id: string
  created_at: Generated<Date>
}

declare module '@/platform/db/database' {
  interface Tables {
    production_destinations: ProductionDestinationsTable
    production_destination_sftp: ProductionDestinationSftpTable
    production_destination_spod: ProductionDestinationSpodTable
    production_requests: ProductionRequestsTable
    production_jobs: ProductionJobsTable
    production_job_items: ProductionJobItemsTable
    production_deliveries: ProductionDeliveriesTable
    production_spod_orders: ProductionSpodOrdersTable
    production_spod_designs: ProductionSpodDesignsTable
  }
}
