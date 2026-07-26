package shop.voenix.json

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Reads and writes an [Instant] as the ISO-8601 JSON string it already looks like on the wire, so a
 * timestamp field can carry the parsed type instead of a string that every reader parses again.
 *
 * `Instant.toString()` always renders UTC, which is why a timestamp sent with an offset comes back
 * normalized.
 */
public object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InstantIso8601", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val value = decoder.decodeString()
        return try {
            Instant.parse(value)
        } catch (exception: DateTimeParseException) {
            throw SerializationException("Expected an ISO-8601 timestamp", exception)
        }
    }
}
