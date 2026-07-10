package shop.voenix.country

import shop.voenix.testing.PostgresIntegrationTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CountryMigrationIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates the current country schema and seed data`() {
        migratedDataSource("country-migration-test").use { dataSource ->
            dataSource.connection.use { connection ->
                val columns =
                    connection
                        .prepareStatement(
                            """
                            SELECT column_name, data_type, udt_name, character_maximum_length,
                                   is_nullable, column_default, is_identity, identity_generation
                            FROM information_schema.columns
                            WHERE table_schema = 'voenix' AND table_name = 'countries'
                            ORDER BY ordinal_position
                            """.trimIndent(),
                        ).use { statement ->
                            statement.executeQuery().use { rows ->
                                buildList {
                                    while (rows.next()) {
                                        add(
                                            listOf(
                                                rows.getString("column_name"),
                                                rows.getString("data_type"),
                                                rows.getString("udt_name"),
                                                rows.getString("character_maximum_length"),
                                                rows.getString("is_nullable"),
                                                rows.getString("column_default"),
                                                rows.getString("is_identity"),
                                                rows.getString("identity_generation"),
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                assertEquals(
                    listOf(
                        listOf("id", "bigint", "int8", null, "NO", null, "YES", "BY DEFAULT"),
                        listOf("name", "character varying", "varchar", "255", "NO", null, "NO", null),
                        listOf("country_code", "character varying", "varchar", "2", "NO", null, "NO", null),
                    ),
                    columns,
                )

                val constraints =
                    connection
                        .prepareStatement(
                            """
                            SELECT pc.conname, pc.contype
                            FROM pg_constraint pc
                            JOIN pg_class pcl ON pcl.oid = pc.conrelid
                            JOIN pg_namespace pn ON pn.oid = pcl.relnamespace
                            WHERE pn.nspname = 'voenix'
                              AND pcl.relname = 'countries'
                              AND pc.contype <> 'n'
                            ORDER BY pc.conname
                            """.trimIndent(),
                        ).use { statement ->
                            statement.executeQuery().use { rows ->
                                buildList {
                                    while (rows.next()) add(rows.getString(1) to rows.getString(2))
                                }
                            }
                        }
                assertEquals(listOf("PK_countries" to "p"), constraints)

                val indexes =
                    connection
                        .prepareStatement(
                            """
                            SELECT indexname, indexdef
                            FROM pg_indexes
                            WHERE schemaname = 'voenix' AND tablename = 'countries'
                            ORDER BY indexname
                            """.trimIndent(),
                        ).use { statement ->
                            statement.executeQuery().use { rows ->
                                buildMap {
                                    while (rows.next()) {
                                        put(rows.getString("indexname"), rows.getString("indexdef"))
                                    }
                                }
                            }
                        }

                assertEquals(
                    setOf("PK_countries", "ix_countries_name_lower", "uk_countries_country_code"),
                    indexes.keys,
                )
                assertEquals(
                    "CREATE UNIQUE INDEX \"PK_countries\" ON voenix.countries USING btree (id)",
                    indexes.getValue("PK_countries"),
                )
                assertEquals(
                    "CREATE UNIQUE INDEX ix_countries_name_lower ON voenix.countries USING btree (lower((name)::text))",
                    indexes.getValue("ix_countries_name_lower"),
                )
                assertEquals(
                    "CREATE UNIQUE INDEX uk_countries_country_code ON voenix.countries USING btree (country_code)",
                    indexes.getValue("uk_countries_country_code"),
                )

                val sequenceName =
                    connection.prepareStatement(
                        "SELECT pg_get_serial_sequence('voenix.countries', 'id')",
                    ).use { statement ->
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getString(1)
                        }
                    }
                assertEquals("voenix.countries_id_seq", sequenceName)

                val sequenceSettings =
                    connection
                        .prepareStatement(
                            """
                            SELECT data_type, start_value, min_value, max_value,
                                   increment_by, cycle, cache_size
                            FROM pg_sequences
                            WHERE schemaname = 'voenix' AND sequencename = 'countries_id_seq'
                            """.trimIndent(),
                        ).use { statement ->
                            statement.executeQuery().use { rows ->
                                rows.next()
                                listOf(
                                    rows.getString("data_type"),
                                    rows.getLong("start_value"),
                                    rows.getLong("min_value"),
                                    rows.getLong("max_value"),
                                    rows.getLong("increment_by"),
                                    rows.getBoolean("cycle"),
                                    rows.getLong("cache_size"),
                                )
                            }
                        }
                assertEquals(
                    listOf("bigint", 1L, 1L, Long.MAX_VALUE, 1L, false, 1L),
                    sequenceSettings,
                )

                val seeds =
                    connection.prepareStatement(
                        "SELECT id, name, country_code FROM voenix.countries ORDER BY id",
                    ).use { statement ->
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    add(
                                        Triple(
                                            rows.getLong("id"),
                                            rows.getString("name"),
                                            rows.getString("country_code"),
                                        ),
                                    )
                                }
                            }
                        }
                    }

                assertEquals(
                    listOf(
                        Triple(1L, "Germany", "DE"),
                        Triple(2L, "France", "FR"),
                        Triple(3L, "Italy", "IT"),
                        Triple(4L, "Austria", "AT"),
                        Triple(5L, "Belgium", "BE"),
                        Triple(6L, "Netherlands", "NL"),
                        Triple(7L, "Spain", "ES"),
                        Triple(8L, "Sweden", "SE"),
                    ),
                    seeds,
                )

                val nextId =
                    connection.prepareStatement(
                        "INSERT INTO voenix.countries (name, country_code) VALUES ('Denmark', 'DK') RETURNING id",
                    ).use { statement ->
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getLong(1)
                        }
                    }
                assertEquals(9L, nextId)
            }
        }
    }
}
