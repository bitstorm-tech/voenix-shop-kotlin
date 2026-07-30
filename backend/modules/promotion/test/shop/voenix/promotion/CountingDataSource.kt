package shop.voenix.promotion

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * A data source that remembers every SQL statement its connections prepare.
 *
 * It is what turns "an empty batch touches no database" from a claim into a measurement: the read
 * either prepares a statement or it does not. The `article` and `prompt` test sources carry the
 * same helper — each module's tests stay compilable on their own rather than sharing a test module
 * for it.
 */
internal class CountingDataSource(private val delegate: DataSource) : DataSource by delegate {
    val statements: MutableList<String> = CopyOnWriteArrayList()

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
}
