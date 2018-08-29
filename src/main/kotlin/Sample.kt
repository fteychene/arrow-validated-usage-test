import arrow.Kind
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.Try
import arrow.data.*
import arrow.typeclasses.ApplicativeError
import java.util.*

// Technical hidden stuff
typealias Validation<E, A> = Kind<ValidatedPartialOf<Nel<E>>, A>

open class ApplicationValidation<E>(
        val A: ApplicativeError<ValidatedPartialOf<Nel<E>>, Nel<E>> = Validated.applicativeError(Nel.semigroup())
) : ApplicativeError<ValidatedPartialOf<Nel<E>>, Nel<E>> by A {

    fun <A, B> applyOn(id: A?, check: (A) -> Kind<ValidatedPartialOf<Nel<E>>, B>, error: E): Kind<ValidatedPartialOf<Nel<E>>, B> =
            if (id == null) raiseError(error.nel())
            else check(id)
}

// Domain

data class Configuration(
        val id: String? = null,
        val url: String? = null,
        val token: String? = null
)

data class OutputConfiguration(
        val id: UUID,
        val url: String = "",
        val token: Option<String> = None
)

// Errors

sealed class ConfigurationError(val output: String) {
    class MissingProperty(val property: String) : ConfigurationError("'$property' is missing")
    class FormatIdError(val value: String) : ConfigurationError("$value is not a UUID format")
    class NotHttpsError(val value: String) : ConfigurationError("$value is not an https URL")
    class InvalidDomainError(val value: String) : ConfigurationError("$value is not in restricted domain")
    object TokenNotBase64Error : ConfigurationError("'token' is not base54 encoded")

    override fun toString(): String = output
}


// Validation rules

private typealias ConfigurationValidation<F> = Validation<ConfigurationError, F>

object ConfigurationRules : ApplicationValidation<ConfigurationError>() {

    fun idFormat(id: String): ConfigurationValidation<UUID> =
            Try { UUID.fromString(id) }.fold(
                    { raiseError(ConfigurationError.FormatIdError(id).nel()) },
                    { just(it) })

    fun httpsUrl(url: String): ConfigurationValidation<Unit> =
            if ("^https://".toRegex().containsMatchIn(url)) just(Unit)
            else raiseError(ConfigurationError.NotHttpsError(url).nel())

    fun checkDomain(url: String): ConfigurationValidation<Unit> =
            if (url.contains("staticdomain.net")) just(Unit)
            else raiseError(ConfigurationError.InvalidDomainError(url).nel())

    fun validateUrl(url: String): ConfigurationValidation<String> =
            map(httpsUrl(url), checkDomain(url), { url })

    fun validateToken(token: String?): ConfigurationValidation<Option<String>> =
            if (token == null) just(None)
            else Try {  String(Base64.getDecoder().decode(token)) }.fold<ConfigurationValidation<Option<String>>>(
                    {raiseError(ConfigurationError.TokenNotBase64Error.nel())},
                    { just(Some(it)) })


    fun validateConfiguration(configuration: Configuration): Validated<Nel<ConfigurationError>, OutputConfiguration> =
            configuration.run {
                map(applyOn(id, { idFormat(it) }, ConfigurationError.MissingProperty("id")),
                        applyOn(url, { validateUrl(it) }, ConfigurationError.MissingProperty("url")),
                        validateToken(token),
                        { (id, url, token) -> OutputConfiguration(id, url, token) }).fix()
            }

}

// Sample
fun main(args: Array<String>) {
    val invalidConfig = ConfigurationRules.validateConfiguration(Configuration(url = "coucou", token = "abcdefgh!_rasc"))
    println(invalidConfig)
    // Invalid(e=NonEmptyList(all=['token' is not base54 encoded, 'id' is missing, coucou is not an https URL, coucou is not in restricted domain]))

    val validConfig = ConfigurationRules.validateConfiguration(Configuration(id="c92a3de0-f898-4664-9625-9fb929058e1b", url = "https://subdomain.staticdomain.net/coucou", token = "SGVsbG8gdG8geW91ICE="))
    println(validConfig)
    // Valid(a=OutputConfiguration(id=c92a3de0-f898-4664-9625-9fb929058e1b, url=https://subdomain.staticdomain.net/coucou, token=Some(Hello to you !)))
}