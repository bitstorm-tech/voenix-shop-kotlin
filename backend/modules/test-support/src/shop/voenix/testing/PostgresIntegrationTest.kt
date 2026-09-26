package shop.voenix.testing

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.reflect.KClass
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Base class of every test that talks to PostgreSQL.
 *
 * One container serves the whole test JVM, and the migration chain runs once, into a template
 * database. Every test class then gets a fresh copy of that template under the same name, so the
 * tests of one class share their database and never see the rows of another class.
 */
public open class PostgresIntegrationTest {
    init {
        recreateDatabaseFor(this::class)
    }

    protected fun migratedDataSource(poolName: String): HikariDataSource =
        dataSource(poolName, DEFAULT_SCHEMA)

    /**
     * Runs the migration chain on [schema], up to [target] when one is named (`"21"` stops after
     * `V21`) and to the end otherwise.
     *
     * A test that needs the state *before* a migration cannot use the shared `voenix` schema — the
     * template already carries it fully migrated — so it migrates a schema of its own in two steps:
     * up to the version before, write the rows, then across.
     */
    protected fun migrate(dataSource: DataSource, schema: String, target: String? = null) {
        migrateSchema(dataSource, schema, target)
    }

    protected fun dataSource(
        poolName: String,
        schema: String? = null,
    ): HikariDataSource = pooledDataSource(TestDatabase.NAME, poolName, schema)

    /**
     * Connection details of the database the current test class works on, for tests that configure
     * the composed application themselves.
     */
    public object TestDatabase {
        public const val NAME: String = "voenix_test"

        public val host: String
            get() = postgres.host

        public val port: Int
            get() = postgres.firstMappedPort

        public val username: String
            get() = postgres.username

        public val password: String
            get() = postgres.password
    }

    private companion object {
        const val DEFAULT_SCHEMA = "voenix"
        const val TEMPLATE_DATABASE = "voenix_template"

        /**
         * Room for every writer a concurrency test starts at once, plus the connection such a test
         * uses to watch them. A pool that is smaller than the writers turns a test into a Hikari
         * acquisition timeout that looks like a database failure of the code under test.
         */
        const val MAXIMUM_POOL_SIZE = 8

        /**
         * Well below Hikari's 30 second default, and far above any acquisition a test may honestly
         * wait for, so pool pressure fails quickly instead of hiding in a slow run.
         */
        const val CONNECTION_TIMEOUT_MILLIS = 10_000L

        /**
         * Started on first use and stopped by Testcontainers when the JVM exits. The Kotlin CLI
         * runs the tests of each module in a JVM of its own, so modules never share a container.
         */
        val postgres: PostgreSQLContainer by lazy {
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply { start() }
        }

        /** The test class whose copy of the template is the current test database. */
        var databaseOwner: KClass<*>? = null

        /**
         * JUnit creates one instance per test and runs the classes of a JVM one after another, so
         * the first instance of a new class is the moment to hand it a fresh database. `FORCE`
         * closes whatever connections the previous class left open.
         */
        @Synchronized
        fun recreateDatabaseFor(testClass: KClass<*>) {
            if (databaseOwner == testClass) return
            if (databaseOwner == null) createTemplate()
            executeOnServer(
                "DROP DATABASE IF EXISTS ${TestDatabase.NAME} WITH (FORCE)",
                "CREATE DATABASE ${TestDatabase.NAME} TEMPLATE $TEMPLATE_DATABASE",
            )
            databaseOwner = testClass
        }

        fun createTemplate() {
            executeOnServer("CREATE DATABASE $TEMPLATE_DATABASE")
            // Postgres copies a template only while no one is connected to it, so the pool that
            // migrates is closed before the first copy.
            pooledDataSource(TEMPLATE_DATABASE, "template-migration", DEFAULT_SCHEMA).use {
                dataSource ->
                migrateSchema(dataSource, DEFAULT_SCHEMA, target = null)
            }
        }

        /** Runs [statements] on the container's own database, which is never copied or dropped. */
        fun executeOnServer(vararg statements: String) {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                .use { connection ->
                    connection.createStatement().use { statement ->
                        statements.forEach(statement::execute)
                    }
                }
        }

        fun migrateSchema(dataSource: DataSource, schema: String, target: String?) {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema(schema)
                .schemas(schema)
                .also { configuration -> target?.let(configuration::target) }
                .load()
                .migrate()
        }

        fun pooledDataSource(
            databaseName: String,
            poolName: String,
            schema: String?,
        ): HikariDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl =
                        "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/" +
                            databaseName +
                            if (schema == null) "" else "?currentSchema=$schema"
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = MAXIMUM_POOL_SIZE
                    connectionTimeout = CONNECTION_TIMEOUT_MILLIS
                    this.poolName = poolName
                }
            )
    }
}
