rootProject.name = "multi-project-source-sets"

include("lib")
include("app")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}