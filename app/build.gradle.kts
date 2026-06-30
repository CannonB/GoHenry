plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
}

subprojects {
    // Redirect build directory to avoid OneDrive file locking issues
    layout.buildDirectory.set(file("C:/temp/gradle-builds/GoHenry/${project.name}"))
}
