plugins {
    id("com.android.library") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
}

allprojects {
    group = "io.github.albertzhang86.heartrate"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set("UI-free Android Bluetooth LE heart-rate library")
                    url.set("https://github.com/albertzhang86/android-ble-heart-rate")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/license/mit")
                        }
                    }
                    scm {
                        url.set("https://github.com/albertzhang86/android-ble-heart-rate")
                        connection.set("scm:git:https://github.com/albertzhang86/android-ble-heart-rate.git")
                    }
                }
            }
        }
    }
}
