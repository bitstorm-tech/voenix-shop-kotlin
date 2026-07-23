package shop.voenix.production.pdf

import java.security.MessageDigest
import java.util.HexFormat

/** The lowercase hex SHA-256 digest recorded with every artifact and verified on every load. */
internal fun sha256Hex(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
