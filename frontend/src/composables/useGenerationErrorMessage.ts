import { computed, type ComputedRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useImageGenerationStore } from '@/stores/shop/imageGeneration'

/**
 * The generator route answers its two infrastructure refusals without a machine-readable code
 * (decision 3 of issue #84), so the HTTP status is the discriminator: `429` is the per-IP rate
 * limit (with a `Retry-After` wait), `413` the application-wide request-size bound.
 */
export const RATE_LIMIT_STATUS = 429
export const PAYLOAD_TOO_LARGE_STATUS = 413

const SECONDS_PER_MINUTE = 60

/**
 * The localized message for the last refused image generation. Every surface that starts a
 * generation shares it, so no view ever shows the backend's raw English message.
 */
export function useGenerationErrorMessage(): ComputedRef<string> {
  const { t } = useI18n()
  const imageGeneration = useImageGenerationStore()

  return computed(() => {
    if (imageGeneration.errorStatus === PAYLOAD_TOO_LARGE_STATUS) {
      return t('mugConfigurator.steps.generate.imageTooLarge')
    }

    if (imageGeneration.errorStatus !== RATE_LIMIT_STATUS) {
      return t('mugConfigurator.steps.generate.errorMessage')
    }

    const waitSeconds = imageGeneration.errorRetryAfterSeconds
    if (waitSeconds === null) {
      return t('mugConfigurator.steps.generate.rateLimited')
    }

    return waitSeconds < SECONDS_PER_MINUTE
      ? t('mugConfigurator.steps.generate.rateLimitedSeconds', waitSeconds)
      : t(
          'mugConfigurator.steps.generate.rateLimitedMinutes',
          Math.ceil(waitSeconds / SECONDS_PER_MINUTE),
        )
  })
}
