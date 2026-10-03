// Top-level build file
plugins {
    id("com.android.application") version "9.3.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
