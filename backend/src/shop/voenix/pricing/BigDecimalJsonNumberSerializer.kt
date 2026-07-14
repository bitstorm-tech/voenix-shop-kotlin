package shop.voenix.pricing

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

object BigDecimalJsonNumberSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimalJsonNumber", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: serializationError("BigDecimal JSON numbers require a JSON encoder")
        jsonEncoder.encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: serializationError("BigDecimal JSON numbers require a JSON decoder")
        val value = jsonDecoder.decodeJsonElement() as? JsonPrimitive
        if (value == null || value.isString) {
            serializationError("Expected a decimal JSON number")
        }
        return value.content.toBigDecimalOrNull()
            ?: serializationError("Invalid decimal JSON number")
    }

    private fun serializationError(message: String): Nothing = throw SerializationException(message)
}
