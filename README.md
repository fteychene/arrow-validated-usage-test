# Validated easy

Some tests to hidde the [Arrow `Validated`](https://arrow-kt.io/docs/datatypes/validated/) context complexity.  

## Domain definition

Domain define input to validate and the result structures to generate.
```
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
```

The validations errors are defined as a `sealed class` :
```
sealed class ConfigurationError(val output: String) {
    class MissingProperty(val property: String) : ConfigurationError("'$property' is missing")
    class FormatIdError(val value: String) : ConfigurationError("$value is not a UUID format")
    class NotHttpsError(val value: String) : ConfigurationError("$value is not an https URL")
    class InvalidDomainError(val value: String) : ConfigurationError("$value is not in restricted domain")
    object TokenNotBase64Error : ConfigurationError("'token' is not base54 encoded")

    override fun toString(): String = output
}
```

## Validation rules
```
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
```

*Result* :  
```
 val invalidConfig = ConfigurationRules.validateConfiguration(Configuration(url = "coucou", token = "abcdefgh"))
 // invalidConfig = Invalid(e=NonEmptyList(all=['id' is missing, coucou is not an https URL, coucou is not in restricted domain]))

 val validConfig = ConfigurationRules.validateConfiguration(Configuration(id="c92a3de0-f898-4664-9625-9fb929058e1b", url = "https://subdomain.staticdomain.net/coucou", token = "SGVsbG8gdG8geW91ICE="))
 // validConfig = Valid(a=OutputConfiguration(id=c92a3de0-f898-4664-9625-9fb929058e1b, url=https://subdomain.staticdomain.net/coucou, token=Some(Hello to you !)))
```