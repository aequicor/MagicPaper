# Browser library ABI

Browser hosts consume the application composition in `:app`. Its directory is
`app/`; the retired `:shared` Gradle project is not kept as an alias. Persisted
Kotlin identities, the existing design-system fonts and engine classpath resources
remain stable through this move. The unused application `Res` generation and
template icon were removed.

The project retains Kotlin 2.4.10, Compose 1.11.1 and kotlinx-browser 0.5.0.
Both `kotlin-dom-api-compat` and `kotlinx-browser-js` publish the actual
`org.w3c.dom.events.EventListener` interface. Linking both causes the Kotlin JS
linker to bind the same class symbol twice.

JS runtime classpaths therefore use the DOM declarations from kotlinx-browser
and exclude kotlin-dom-api-compat. Compile classpaths keep the dependency metadata;
Wasm classpaths are unchanged. Removing kotlinx-browser instead is insufficient:
Compose UI and Ktor use its DOM overloads and array conversion functions.

Compose Foundation's clipboard implementation also calls the legacy top-level
`EventListener(handler: (Event) -> Unit): EventListener` SAM factory. This factory
was supplied by kotlin-dom-api-compat and is absent from kotlinx-browser 0.5.0.
`core/platform/src/jsMain/kotlin/org/w3c/dom/events/EventListenerFactory.kt`
provides exactly this binary signature, constructing a JavaScript listener object
whose `handleEvent` function is the supplied callback. It does not replace any
DOM type or change how events are registered or removed.

The declarations can be checked in the official published source archives:

- [Kotlin DOM compatibility 2.4.10 sources](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-dom-api-compat/2.4.10/kotlin-dom-api-compat-2.4.10-sources.jar), `jsMain/org.w3c.dom.events.kt`.
- [kotlinx-browser JS 0.5.0 sources](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-browser-js/0.5.0/kotlinx-browser-js-0.5.0-sources.jar), `jsMain/org.w3c/org.w3c.dom.events.kt`.

Both web executables set `-Xpartial-linkage-loglevel=ERROR`. Kotlin 2.4 otherwise
[defaults to silent partial linkage](https://kotlinlang.org/docs/whatsnew24.html#consistent-partial-library-linkage-across-kotlin-compilers),
which can report a successful build while generating runtime `IrLinkageError`
stubs. The strict JS and Wasm executable tasks are required migration checks.
When upgrading Kotlin, Compose or kotlinx-browser, review this boundary and remove
the exclusion and factory together once the libraries provide a single compatible
DOM API. Do not suppress linkage errors.
