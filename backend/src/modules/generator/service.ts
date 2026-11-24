import type { ArticleCatalog } from '@/modules/article'
import type { PromptCatalog } from '@/modules/prompt'
import { errorDetails, type Logger } from '@/platform/logging'
import type { ImageGenerator, RawImage } from './image-generator'
import {
  describeOwner,
  GENERATION_COST,
  type MagicCoins,
  type MagicCoinsOwner,
} from './magic-coins'

/**
 * How one generation of a received upload ended. A database failure on the way is an exception,
 * which the app's `onError` answers with `500`, never one of these.
 */
export type GenerationOutcome =
  | { kind: 'generated'; image: RawImage }
  | { kind: 'insufficientCoins' }
  | { kind: 'articleUnavailable' }
  | { kind: 'promptUnavailable' }
  /** The image provider did not deliver an image. */
  | { kind: 'upstreamFailure' }

export type GeneratorService = ReturnType<typeof createGeneratorService>

/**
 * The order of one generation after the upload was checked: a read-only balance check, the
 * article's print format, the prompt text, an atomic upfront coin deduction, and then the provider
 * call — refunding the coin if the provider fails or throws. A visitor without coins is told so
 * before anything is looked up; an unknown article or prompt spends nothing; concurrent requests
 * with one coin left can never trigger multiple paid provider calls; and nobody pays for an image
 * the provider never delivered.
 */
export function createGeneratorService({
  coins,
  articleCatalog,
  promptCatalog,
  generateImage,
  logger,
}: {
  coins: MagicCoins
  articleCatalog: Pick<ArticleCatalog, 'printFormats'>
  promptCatalog: Pick<PromptCatalog, 'composedText'>
  generateImage: ImageGenerator
  logger: Logger
}) {
  const refund = async (owner: MagicCoinsOwner) => {
    try {
      await coins.refund(owner)
    } catch (error) {
      logger.error(
        { error: errorDetails(error) },
        `Magic Coin refund failed for ${describeOwner(owner)}`,
      )
    }
  }

  return {
    generate: async (
      owner: MagicCoinsOwner,
      upload: { image: RawImage; promptId: number; articleId: number },
    ): Promise<GenerationOutcome> => {
      if ((await coins.readBalance(owner)) < GENERATION_COST) return { kind: 'insufficientCoins' }
      const ratio = (await articleCatalog.printFormats([upload.articleId])).get(upload.articleId)
      if (ratio === undefined) return { kind: 'articleUnavailable' }
      const prompt = await promptCatalog.composedText(upload.promptId)
      if (prompt === null) return { kind: 'promptUnavailable' }
      if (!(await coins.spend(owner))) return { kind: 'insufficientCoins' }
      let generated: RawImage | null
      try {
        generated = await generateImage(upload.image, prompt, ratio)
      } catch (error) {
        await refund(owner)
        throw error
      }
      if (generated === null) {
        await refund(owner)
        return { kind: 'upstreamFailure' }
      }
      return { kind: 'generated', image: generated }
    },
  }
}
