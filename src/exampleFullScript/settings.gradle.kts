rootProject.name = "multiproject"

include(":react")
project(":react").projectDir = File("react")

include(":lambda1")
project(":lambda1").projectDir = File("src/lambda/lambda1")

include(":lambda2")
project(":lambda2").projectDir = File("src/lambda/lambda2")

plubingManagement {
    repositories {
        gradlePluginPortal()
    }
}