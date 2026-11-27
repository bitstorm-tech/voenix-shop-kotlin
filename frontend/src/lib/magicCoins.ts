/** Machine-readable error codes returned by the Magic Coins / generator endpoints. */
export const INSUFFICIENT_MAGIC_COINS_CODE = 'INSUFFICIENT_MAGIC_COINS'
export const MAGIC_COINS_REQUEST_BLOCKED_CODE = 'MAGIC_COINS_REQUEST_BLOCKED'
export const MAGIC_COIN_REQUEST_ALREADY_PENDING_CODE = 'MAGIC_COIN_REQUEST_ALREADY_PENDING'
export const MAGIC_COINS_AVAILABLE_CODE = 'MAGIC_COINS_AVAILABLE'

export type CustomerMagicCoinRequestStatus = 'CAN_REQUEST' | 'PENDING' | 'BLOCKED'
