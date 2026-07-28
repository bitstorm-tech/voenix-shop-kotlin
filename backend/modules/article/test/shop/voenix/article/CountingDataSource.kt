package shop.voenix.article

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * A data source that remembers every SQL statement its connections prepare.
 *
 * This is what turns "no N+1" from a claim into a measurement: the statements a list of one article
 * runs and the statements a list of three runs have to be the same ones.
 */
internal class CountingDataSource(private val delegate: DataSource) : DataSource by delegate {
    val statements: MutableList<String> = CopyOnWriteArrayList()

    /**
     * The recorded statements with the shape of their parameter lists normalized. Batching one more
     * article into the same query turns `= ?` into `IN (?, ?)`, and that is exactly the difference
     * that must not count as another statement.
     */
    fun normalizedStatements(): List<String> = statements.map { statement ->
        statement.replace(PLACEHOLDER_PREDICATE, "= ?")
    }

    override fun getConnection(): Connection = counting(delegate.connection)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = counting(delegate.getConnection(username, password))

    private fun counting(connection: Connection): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            if (method.name == "prepareStatement") {
                (arguments?.firstOrNull() as? String)?.let(statements::add)
            }
            try {
                if (arguments == null) {
                    method.invoke(connection)
                } else {
                    method.invoke(connection, *arguments)
                }
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection

    private companion object {
        val PLACEHOLDER_PREDICATE = Regex("""IN \(\?(, \?)*\)|= \?""")
    }
}
