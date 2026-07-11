package shop.voenix.db

import javax.sql.DataSource

object CountrySchemaCompatibility {
    fun requiresBaseline(dataSource: DataSource): Boolean =
        dataSource.connection.use { connection ->
            val hasCountries =
                connection
                    .prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM information_schema.tables
                            WHERE table_schema = current_schema() AND table_name = 'countries'
                        )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getBoolean(1)
                        }
                    }
            val hasFlywayHistory =
                connection
                    .prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM information_schema.tables
                            WHERE table_schema = current_schema() AND table_name = 'flyway_schema_history'
                        )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getBoolean(1)
                        }
                    }
            hasCountries && !hasFlywayHistory
        }

    fun verify(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            val schema =
                checkNotNull(connection.schema) {
                    "Database.SearchPath does not resolve to an existing PostgreSQL schema"
                }
            val quotedSchema = connection.quoteIdentifier(schema)
            val columns =
                connection
                    .prepareStatement(
                        """
                        SELECT column_name, data_type, udt_name, character_maximum_length,
                               is_nullable, column_default, is_identity, identity_generation
                        FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = 'countries'
                        ORDER BY ordinal_position
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, schema)
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
            val expectedColumns =
                listOf(
                    listOf("id", "bigint", "int8", null, "NO", null, "YES", "BY DEFAULT"),
                    listOf("name", "character varying", "varchar", "255", "NO", null, "NO", null),
                    listOf(
                        "country_code",
                        "character varying",
                        "varchar",
                        "2",
                        "NO",
                        null,
                        "NO",
                        null,
                    ),
                )
            check(columns == expectedColumns) {
                "Existing $schema.countries columns are incompatible with the Country Flyway baseline: $columns"
            }

            val constraints =
                connection
                    .prepareStatement(
                        """
                        SELECT pc.conname, pc.contype
                        FROM pg_constraint pc
                        JOIN pg_class pcl ON pcl.oid = pc.conrelid
                        JOIN pg_namespace pn ON pn.oid = pcl.relnamespace
                        WHERE pn.nspname = ?
                          AND pcl.relname = 'countries'
                          AND pc.contype <> 'n'
                        ORDER BY pc.conname
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, schema)
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) add(rows.getString(1) to rows.getString(2))
                            }
                        }
                    }
            check(constraints == listOf("PK_countries" to "p")) {
                "Existing $schema.countries constraints are incompatible: $constraints"
            }

            val triggers =
                connection
                    .prepareStatement(
                        """
                        SELECT trigger_name
                        FROM information_schema.triggers
                        WHERE event_object_schema = ?
                          AND event_object_table = 'countries'
                        ORDER BY trigger_name
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, schema)
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) add(rows.getString(1))
                            }
                        }
                    }
            check(triggers.isEmpty()) {
                "Existing $schema.countries triggers are incompatible: $triggers"
            }

            val indexes =
                connection
                    .prepareStatement(
                        """
                        SELECT indexname, indexdef
                        FROM pg_indexes
                        WHERE schemaname = ? AND tablename = 'countries'
                        ORDER BY indexname
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, schema)
                        statement.executeQuery().use { rows ->
                            buildMap {
                                while (rows.next()) {
                                    put(rows.getString("indexname"), rows.getString("indexdef"))
                                }
                            }
                        }
                    }
            val primaryKeyIndex =
                "CREATE UNIQUE INDEX \"PK_countries\" ON $quotedSchema.countries USING btree (id)"
            val canonicalIndexes =
                mapOf(
                    "PK_countries" to primaryKeyIndex,
                    "ux_countries_country_code" to
                        "CREATE UNIQUE INDEX ux_countries_country_code ON $quotedSchema.countries USING btree (country_code)",
                    "ux_countries_name_lower" to
                        "CREATE UNIQUE INDEX ux_countries_name_lower ON $quotedSchema.countries USING btree (lower((name)::text))",
                )
            val legacyIndexes =
                mapOf(
                    "PK_countries" to primaryKeyIndex,
                    "ix_countries_name_lower" to
                        "CREATE UNIQUE INDEX ix_countries_name_lower ON $quotedSchema.countries USING btree (lower((name)::text))",
                    "uk_countries_country_code" to
                        "CREATE UNIQUE INDEX uk_countries_country_code ON $quotedSchema.countries USING btree (country_code)",
                )
            check(indexes == canonicalIndexes || indexes == legacyIndexes) {
                "Existing $schema.countries indexes are incompatible with the Country Flyway baseline: $indexes"
            }

            val sequenceName =
                connection
                    .prepareStatement("SELECT pg_get_serial_sequence(?, 'id')")
                    .use { statement ->
                        statement.setString(1, "$quotedSchema.countries")
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getString(1)
                        }
                    }
            check(sequenceName == "$quotedSchema.countries_id_seq") {
                "Existing $schema.countries identity sequence is incompatible: $sequenceName"
            }

            val sequenceSettings =
                connection
                    .prepareStatement(
                        """
                        SELECT data_type, start_value, min_value, max_value,
                               increment_by, cycle, cache_size
                        FROM pg_sequences
                        WHERE schemaname = ? AND sequencename = 'countries_id_seq'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, schema)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "Missing $schema.countries_id_seq" }
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
            check(
                sequenceSettings ==
                    listOf("bigint", 1L, 1L, Long.MAX_VALUE, 1L, false, 1L),
            ) {
                "Existing $schema.countries identity sequence settings are incompatible: $sequenceSettings"
            }

            verifySupplierRelationshipIfPresent(connection, schema, quotedSchema)
        }
    }

    private fun verifySupplierRelationshipIfPresent(
        connection: java.sql.Connection,
        schema: String,
        quotedSchema: String,
    ) {
        val hasSuppliers =
            connection
                .prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM information_schema.tables
                        WHERE table_schema = ? AND table_name = 'suppliers'
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, schema)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getBoolean(1)
                    }
                }
        if (!hasSuppliers) return

        val supplierCountryColumn =
            connection
                .prepareStatement(
                    """
                    SELECT data_type, udt_name, is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'suppliers'
                      AND column_name = 'country_id'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, schema)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            listOf(
                                rows.getString("data_type"),
                                rows.getString("udt_name"),
                                rows.getString("is_nullable"),
                            )
                        } else {
                            null
                        }
                    }
                }
        check(supplierCountryColumn == listOf("bigint", "int8", "YES")) {
            "Existing suppliers.country_id column is incompatible: $supplierCountryColumn"
        }

        val foreignKey =
            connection
                .prepareStatement(
                    """
                    SELECT pc.conname,
                           source_column.attname,
                           target_namespace.nspname,
                           target_table.relname,
                           target_column.attname,
                           pc.confdeltype,
                           pc.confupdtype,
                           pc.confmatchtype,
                           pc.condeferrable,
                           pc.condeferred
                    FROM pg_constraint pc
                    JOIN pg_class source_table ON source_table.oid = pc.conrelid
                    JOIN pg_namespace source_namespace
                      ON source_namespace.oid = source_table.relnamespace
                    JOIN pg_attribute source_column
                      ON source_column.attrelid = source_table.oid
                     AND source_column.attnum = pc.conkey[1]
                    JOIN pg_class target_table ON target_table.oid = pc.confrelid
                    JOIN pg_namespace target_namespace
                      ON target_namespace.oid = target_table.relnamespace
                    JOIN pg_attribute target_column
                      ON target_column.attrelid = target_table.oid
                     AND target_column.attnum = pc.confkey[1]
                    WHERE source_namespace.nspname = ?
                      AND source_table.relname = 'suppliers'
                      AND pc.contype = 'f'
                      AND pc.conname = 'FK_suppliers_countries_country_id'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, schema)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) return@use null
                        listOf(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getString(6),
                            rows.getString(7),
                            rows.getString(8),
                            rows.getBoolean(9),
                            rows.getBoolean(10),
                        )
                    }
                }
        check(
            foreignKey ==
                listOf(
                    "FK_suppliers_countries_country_id",
                    "country_id",
                    schema,
                    "countries",
                    "id",
                    "n",
                    "a",
                    "s",
                    false,
                    false,
                ),
        ) {
            "Existing supplier-country foreign key is incompatible: $foreignKey"
        }

        val supplierIndex =
            connection
                .prepareStatement(
                    """
                    SELECT indexdef FROM pg_indexes
                    WHERE schemaname = ?
                      AND tablename = 'suppliers'
                      AND indexname = 'IX_suppliers_country_id'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, schema)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getString(1) else null
                    }
                }
        check(
            supplierIndex ==
                "CREATE INDEX \"IX_suppliers_country_id\" ON $quotedSchema.suppliers USING btree (country_id)",
        ) {
            "Existing supplier-country index is incompatible: $supplierIndex"
        }
    }

    private fun java.sql.Connection.quoteIdentifier(identifier: String): String =
        prepareStatement("SELECT quote_ident(?)").use { statement ->
            statement.setString(1, identifier)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
}
