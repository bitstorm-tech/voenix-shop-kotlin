import { computed, type ComputedRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useImageGenerationStore } from '@/stores/shop/imageGeneration'

/**
 * The generator route answers its refusals without a machine-readable code (decision 3 of issue
 * #84), so the HTTP status is the discriminator: `429` is the per-IP rate limit (with a
 * `Retry-After` wait), `413` the application-wide request-size bound, and `400` a refusal the
 * generator itself decided — reported as a field error on the part it blames.
 */
export const RATE_LIMIT_STATUS = 429
export const PAYLOAD_TOO_LARGE_STATUS = 413
export const VALIDATION_FAILED_STATUS = 400

/** The part the image is sent under, and therefore the key a refused image is reported on. */
const IMAGE_FIELD = 'image'

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

    // The generator's own bounds — 10 MiB and JPEG/PNG/WebP — are refused long before the
    // application-wide `413`, as a `400` on the `image` part. Both causes share one message on
    // purpose: the backend distinguishes them only in the English text of the field error, and
    // reading that text would make the copy a hostage of a backend string.
    if (
      imageGeneration.errorStatus === VALIDATION_FAILED_STATUS &&
      imageGeneration.errorFieldErrors[IMAGE_FIELD] !== undefined
    ) {
      return t('mugConfigurator.steps.generate.imageRejected')
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
