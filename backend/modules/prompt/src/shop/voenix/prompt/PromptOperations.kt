package shop.voenix.prompt

import shop.voenix.image.ImageUpload
import shop.voenix.operation.OperationResult

/**
 * The admin lifecycle of a prompt.
 *
 * No operation here answers with [OperationResult.Conflict], and that is a property of the whole
 * group rather than an accident: a prompt has no unique name, its position is decided under a lock,
 * and every reference a client can get wrong — category, subcategory, slot variant, price — is
 * reported as a field error of the body that named it.
 *
 * There is no delete either. A prompt is retired by setting `archived`, because orders and carts
 * refer to prompts that must stay readable.
 */
internal interface PromptOperations {
    /** Every prompt in display order, as overview rows with the small price projection. */
    suspend fun list(): OperationResult<List<PromptListItem>>

    suspend fun get(id: Long): OperationResult<Prompt>

    /**
     * Creates a prompt behind the last one. A category, subcategory, or slot variant that does not
     * exist produces [OperationResult.Invalid] on the field that named it.
     */
    suspend fun create(input: PromptInput): OperationResult<Prompt>

    /**
     * Replaces every stored value of a prompt except its position, including its whole set of slot
     * variants and the calculation inputs of the price it owns.
     */
    suspend fun update(
        id: Long,
        input: PromptInput,
    ): OperationResult<Prompt>

    /**
     * Stores an example image and answers with the file name a following [create] or [update]
     * submits as `exampleImageFilename`.
     *
     * The upload happens before the prompt that refers to it exists, which is what keeps the two
     * write operations plain JSON. A file no prompt ever names stays behind as an accepted orphan.
     */
    suspend fun storeExampleImage(upload: ImageUpload): OperationResult<ExampleImage>
}
