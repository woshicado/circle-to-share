package eu.woshicado.circletoshare

/**
 * What the Play build adds to the licenses page's [components]: Play
 * Billing and what it pulls in (Play services: base, basement, tasks,
 * location, places-placereport). The open-source code compiled into these
 * libraries is listed from their own notices (GooglePlayNoticesTask in
 * app/build.gradle.kts). Keep in step with THIRD_PARTY_NOTICES.md:
 * ./gradlew -Pplay :app:dependencies --configuration playReleaseRuntimeClasspath
 */
internal val storeComponents = listOf(
    Component("Google Play Billing Library", "Google LLC", License.ANDROID_SDK),
    Component("Google Play services", "Google LLC", License.ANDROID_SDK),
    Component("Google Data Transport", "Google LLC", License.APACHE_2),
    Component("Firebase Encoders", "Google LLC", License.APACHE_2),
    Component("javax.inject", "The JSR-330 Expert Group", License.APACHE_2),
)
