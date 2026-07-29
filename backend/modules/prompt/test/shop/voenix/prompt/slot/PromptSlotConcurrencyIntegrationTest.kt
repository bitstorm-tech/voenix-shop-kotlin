package shop.voenix.prompt.slot

import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.PromptTestSchema
import shop.voenix.prompt.persistence.PromptSlotRepository
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the slot positions survive concurrent writers.
 *
 * A slot create is the only write that decides a position, and it queues on the `SLOT` anchor row.
 * That is what replaces the legacy retry loop: the position conflict it retried cannot happen, so
 * two creates that start at the same time simply append one behind the other.
 *
 * The second rule these tests pin down is what slots deliberately do *not* do: nothing closes the
 * gap a delete leaves, and no later create fills it.
 */
internal class PromptSlotConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `two concurrent creates append one behind the other without a retry`() {
        migratedDataSource("prompt-slot-concurrent-create-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            val slots = slotService(dataSource)

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async(Dispatchers.IO) { slots.create(PromptSlotInput(name = "First")) },
                            async(Dispatchers.IO) {
                                slots.create(PromptSlotInput(name = "Second"))
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                assertEquals(
                    2,
                    results.count { result -> result is OperationResult.Success },
                    "Both creates hold the ordering anchor in turn, so neither may fail: $results",
                )
            }

            assertEquals(
                listOf(1, 2),
                PromptTestSchema.orderedSlots(dataSource).map { (_, position) -> position },
            )
        }
    }

    @Test
    fun `concurrent case-variant creates leave one row and one conflict`() {
        migratedDataSource("prompt-slot-concurrent-name-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            val slots = slotService(dataSource)

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async(Dispatchers.IO) { slots.create(PromptSlotInput(name = "Race")) },
                            async(Dispatchers.IO) { slots.create(PromptSlotInput(name = "RACE")) },
                        )
                        .map { deferred -> deferred.await() }
                }

                assertEquals(1, results.count { result -> result is OperationResult.Success })
                assertEquals(1, results.count { result -> result === OperationResult.Conflict })
            }

            assertEquals(1, PromptTestSchema.orderedSlots(dataSource).size)
        }
    }

    @Test
    fun `the position a delete leaves behind is never reused`() {
        migratedDataSource("prompt-slot-gap-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "First", "Second", "Third")
            val slots = slotService(dataSource)

            runBlocking {
                assertEquals(OperationResult.Success(Unit), slots.delete(2))

                // The next create appends behind the last remaining position, not into the gap.
                assertIs<OperationResult.Success<PromptSlot>>(
                    slots.create(PromptSlotInput(name = "Fourth"))
                )
            }

            assertEquals(
                listOf("First" to 1, "Third" to 3, "Fourth" to 4),
                PromptTestSchema.orderedSlots(dataSource),
            )
        }
    }

    private fun slotService(dataSource: DataSource): PromptSlotService =
        PromptSlotService(PromptSlotRepository(Database.connect(datasource = dataSource)))
}
