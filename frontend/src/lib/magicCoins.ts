export const MAGIC_COINS_ROUTE = '/magic-coins'
export const IMAGE_GENERATION_MAGIC_COIN_COST = 1
export const INSUFFICIENT_MAGIC_COINS_CODE = 'INSUFFICIENT_MAGIC_COINS'

export const magicCoinsPlans = [
  {
    id: 'starter',
    coins: 25,
    bonusCoins: 0,
    priceInCents: 790,
    featured: false,
  },
  {
    id: 'studio',
    coins: 80,
    bonusCoins: 10,
    priceInCents: 1990,
    featured: true,
  },
  {
    id: 'reserve',
    coins: 180,
    bonusCoins: 30,
    priceInCents: 3490,
    featured: false,
  },
] as const

export type MagicCoinsPlan = (typeof magicCoinsPlans)[number]
export type MagicCoinsPlanId = MagicCoinsPlan['id']

export const featuredMagicCoinsPlan = getMagicCoinsPlan('studio')

export function getMagicCoinsPlan(planId: MagicCoinsPlanId) {
  return magicCoinsPlans.find((plan) => plan.id === planId) ?? magicCoinsPlans[0]
}

export function getTotalMagicCoins(plan: Pick<MagicCoinsPlan, 'coins' | 'bonusCoins'>) {
  return plan.coins + plan.bonusCoins
}
