package shop.voenix.prompt.slot

import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.PromptTestSchema
import shop.voenix.prompt.persistence.PromptSlotVariantRepository
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the one unique rule of the slot variants survives concurrent writers.
 *
 * A variant name is unique **globally and case-insensitively** — not per slot — and the duplicate
 * test of [PromptSlotVariantAdminIntegrationTest] proves that for two writes that happen one after
 * the other. What it cannot prove is the race: two admins creating the same name at the same time
 * in *different* slots, which is the case that distinguishes a global index from a per-slot one.
 *
 * Nothing but the index can decide this. There is no lock a variant write takes, and a preliminary
 * "does this name exist" read would be exactly the check that races, so the answer has to come from
 * the statement itself: one writer stores its row, the other is answered a conflict.
 */
internal class PromptSlotVariantConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `concurrent case-variant creates in two slots leave one row and one conflict`() {
        migratedDataSource("prompt-slot-variant-concurrent-name-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "Background", "Style")
            val variants = variantService(dataSource)

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async(Dispatchers.IO) {
                                variants.create(variantInput(slotId = 1, name = "Sunset"))
                            },
                            async(Dispatchers.IO) {
                                variants.create(variantInput(slotId = 2, name = "SUNSET"))
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                assertEquals(
                    1,
                    results.count { result -> result is OperationResult.Success },
                    "Exactly one of the two writers may store the name: $results",
                )
                assertEquals(
                    1,
                    results.count { result -> result === OperationResult.Conflict },
                    "The loser of the race is a conflict, not a failure: $results",
                )
            }

            // Whichever writer won, exactly one row exists — in whichever of the two slots it was.
            assertEquals(1, PromptTestSchema.storedVariants(dataSource).size)
        }
    }

    private fun variantInput(
        slotId: Long,
        name: String,
    ): PromptSlotVariantInput =
        PromptSlotVariantInput(slotId = slotId, name = name, prompt = "at $name")

    private fun variantService(dataSource: DataSource): PromptSlotVariantService =
        PromptSlotVariantService(
            PromptSlotVariantRepository(Database.connect(datasource = dataSource))
        )
}
