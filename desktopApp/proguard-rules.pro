# Release ProGuard rules for the MagicPaper desktop executable.
#
# ProGuard's optimizer crashes (StackGeneralizationException) on the bytecode of
# large Kotlin suspend functions, e.g. PlanningExecutionService.reviewAcceptance.
# Keep shrinking but skip optimization.
-dontoptimize

# Bundled libraries reference optional integrations that are not on the runtime
# classpath (test frameworks, JMX bridges, mail/JMS appenders, Ant tasks, etc.).
# Those code paths are never exercised by the application; silence the
# unresolved-reference warnings instead of pulling unused optional dependencies.

# Playwright ships optional JUnit 5 extensions; JUnit is not a runtime dependency.
-dontwarn org.junit.jupiter.**
-dontwarn org.junit.platform.**
-dontwarn com.microsoft.playwright.impl.junit.**

# log4j 1.x bridge: optional JMS, SMTP, ZeroConf and JMX sinks.
-dontwarn javax.jms.**
-dontwarn javax.mail.**
-dontwarn javax.jmdns.**
-dontwarn com.sun.jdmk.**
-dontwarn com.ibm.uvm.**
-dontwarn org.apache.avalon.**
-dontwarn org.apache.log.**

# commons-logging optional servlet listener.
-dontwarn javax.servlet.**
-dontwarn org.apache.commons.logging.**

# Jetty: optional JMX beans, ee10 servlet module and version-specific members.
-dontwarn org.eclipse.jetty.**
-dontwarn java.lang.invoke.MethodHandle

# nu.validator / Saxon / RELAX verifiers: optional Ant, Xerces, XML resolver,
# chardet and ISO-RELAX/Swift bindings.
-dontwarn org.apache.tools.ant.**
-dontwarn org.apache.xerces.**
-dontwarn org.apache.xml.resolver.**
-dontwarn org.mozilla.intl.chardet.**
-dontwarn jp.co.swiftinc.**
-dontwarn jp.gr.xml.relax.**
-dontwarn nu.validator.**
-dontwarn net.sf.saxon.**
-dontwarn org.iso_relax.**

# OSHI optionally probes LibreHardwareMonitor on Windows.
-dontwarn io.github.pandalxb.**

# commons-io JDK8 direct-buffer cleaner fallback.
-dontwarn sun.misc.Cleaner

# Playwright serializes its option/protocol types via Gson with custom type
# adapters; ProGuard explicitly reports these classes must be kept.
-keep class com.microsoft.playwright.options.** { *; }
-keep class com.microsoft.playwright.impl.LocatorImpl { *; }

# JNA's native dispatch library (jnidispatch) calls private static bridge
# methods of com.sun.jna.Native (fromNative/toNative) through JNI. Shrinking
# sees no Java caller and removes them; every later NativeLibrary load then
# dies with UnsatisfiedLinkError "Can't obtain static method fromNative",
# which broke activation forwarding in the packaged executable. Keep the whole
# library, including platform mappings and NativeMapped implementations.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.NativeMapped { *; }
# JNA Structures map native layouts through their declared Java fields; shrinking
# "unused" public fields corrupts getFieldOrder() and every native call of the
# structure (oshi battery probe -> the paper animation never enables).
-keepclassmembers class * extends com.sun.jna.Structure { *; }

# Declarations entered only for the native side must survive shrinking.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ServiceLoader providers are invisible to shrinking: nothing in Java code names
# them, only META-INF/services resources do, and ProGuard copies those verbatim.
# Without these rules the packaged executable dies on startup with
# ServiceConfigurationError (ktor engine, Decompose main-thread checker, Swing
# dispatcher) or later while parsing/validation (validator, Jetty encoders).
# The list mirrors the META-INF/services entries of the bundled classpath.
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements com.arkivanov.decompose.mainthread.MainThreadChecker { *; }
-keep class * implements org.eclipse.jetty.http.HttpFieldPreEncoder { *; }
-keep class * implements org.relaxng.datatype.DatatypeLibraryFactory { *; }
# nu.validator discovers its schema readers, datatype and regex engines through
# its vendored ServiceLoader interfaces; keep the whole discovery graph.
-keep class nu.validator.** { *; }
# JDK SPI implemented by the bundled Saxon; selected via TransformerFactory.
-keep class net.sf.saxon.TransformerFactoryImpl { *; }
