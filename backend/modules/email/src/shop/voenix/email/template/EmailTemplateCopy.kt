package shop.voenix.email.template

/**
 * Sentences that more than one email says. A wording used by two mails needs one owner, otherwise
 * the second copy quietly drifts away from the first; and a shared sentence has no natural home in
 * either the HTML or the plain-text layout, because it serves both variants equally.
 */
internal object EmailTemplateCopy {
    /**
     * Why the link is worth keeping, and what it opens. It is the same sentence in both variants,
     * because it is the same promise: this mail *is* the handle to the order, the link does not
     * expire, and whoever holds it can read this one order — and nothing else.
     *
     * It is not `private` because the order confirmation and the shipping notification both print
     * it, and the renderer test pins "the same sentence reaches both variants" without copying it.
     */
    const val DURABLE_LINK_HINT: String =
        "Bewahre diese E-Mail auf: Der Link bleibt dauerhaft gültig und zeigt dir jederzeit den " +
            "aktuellen Stand dieser Bestellung, auch ohne Konto. Wer den Link hat, kann diese " +
            "eine Bestellung ansehen — gib ihn deshalb nicht weiter."
}
