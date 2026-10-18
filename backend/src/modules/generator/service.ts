import type { ArticleCatalog } from '@/modules/article'
import type { PromptCatalog } from '@/modules/prompt'
import type { Logger } from '@/platform/logging'
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
 * The order of one generation after the upload was checked: the balance, the article's print
 * format, the prompt text, the generation, and only then the spend. A visitor without coins is told
 * so before anything is looked up; an unknown article is the cheapest ending and loads no prompt;
 * nobody pays for an image the provider never delivered. The article is asked for its ratio only:
 * whether it can be bought is the cart's question.
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
  /**
   * A failed spend only logs: the image exists, and withholding it would punish the customer for a
   * defect on our side.
   */
  const spend = async (owner: MagicCoinsOwner) => {
    try {
      if (!(await coins.spend(owner))) {
        logger.warn(
          `Magic Coin spend skipped for ${describeOwner(owner)}: insufficient balance at spend time`,
        )
      }
    } catch (error) {
      logger.error({ err: error }, `Magic Coin spend failed for ${describeOwner(owner)}`)
    }
  }

  return {
    generate: async (
      owner: MagicCoinsOwner,
      upload: { image: RawImage; promptId: number; articleId: number },
    ): Promise<GenerationOutcome> => {
      if ((await coins.balance(owner)) < GENERATION_COST) return { kind: 'insufficientCoins' }
      const ratio = (await articleCatalog.printFormats([upload.articleId])).get(upload.articleId)
      if (ratio === undefined) return { kind: 'articleUnavailable' }
      const prompt = await promptCatalog.composedText(upload.promptId)
      if (prompt === null) return { kind: 'promptUnavailable' }
      const generated = await generateImage(upload.image, prompt, ratio)
      if (generated === null) return { kind: 'upstreamFailure' }
      await spend(owner)
      return { kind: 'generated', image: generated }
    },
  }
}
