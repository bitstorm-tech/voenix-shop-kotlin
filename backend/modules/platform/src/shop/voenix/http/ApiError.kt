package shop.voenix.http

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import shop.voenix.validation.ValidationErrors

@Serializable
public data class ApiError(
    public val message: String,
    public val errors: ValidationErrors = emptyMap(),
    /**
     * Optional machine-readable error code. Omitted from the JSON body when `null`, so error bodies
     * without a code serialize exactly as they did before this field existed.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val code: String? = null,
)
