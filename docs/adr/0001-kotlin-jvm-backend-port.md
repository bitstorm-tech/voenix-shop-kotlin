# Choose Kotlin/JVM for the Backend Port

The backend port will target Kotlin/JVM, not Kotlin/Native, because the existing ASP.NET Core backend depends on mature server, database, PDF, image, payment, email, SFTP, and background-job capabilities that map far better to the JVM ecosystem. The planned stack is Ktor, Exposed, kotlinx.serialization, coroutines, and a migration tool chosen by proof work between Flyway and Liquibase.

## Considered Options

- Kotlin/JVM with Ktor: preferred; mature libraries and deployable backend runtime.
- Kotlin/Native: rejected for now; higher integration risk for this backend's library surface.
- Spring Boot: viable, but heavier than needed for a focused port and less aligned with the current Ktor recommendation.

## Consequences

- The port should validate framework choices with proof work before production slices.
- EF Core behavior will be rewritten deliberately rather than translated one-to-one.
- QuestPDF, ImageSharp, ASP.NET Identity, and background services need explicit replacement decisions.
