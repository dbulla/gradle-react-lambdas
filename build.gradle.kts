group = "com.nurflugel"
version = "0.0.1-SNAPSHOT"

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "1.3.72"
    `kotlin-dsl`
    `maven-publish`

    id("at.phatbl.shellexec") version "1.5.2"
    id("com.github.ben-manes.versions") version "0.36.0"
    id("com.dorongold.task-tree") version "1.5"
    id("com.gradle.plugin-publish") version "0.12.0"
}

gradlePlugin {
    plugins {
        create("reactLambdas") {
            id = "com.nurflugel.gradle.plugins.reactlambdas"
            implementationClass = "com.nurflugel.gradle.plugins.reactlambdas.ReactLambdasPlugin"
            displayName = "Gradle React-Lambdas plugin"
        }
    }
}

repositories {
    mavenCentral()
    jcenter()
    gradlePluginPortal()
    // your corporate plugin repo here
}

val taskTreeVersion = "1.5"
val versionsVersion = "0.36.0"
val shellExecVersion = "1.5.2"

dependencies {
    implementation("com.github.ben-manes:gradle-versions-plugin:$versionsVersion")
    implementation("gradle.plugin.com.dorongold.plugins:task-tree:$taskTreeVersion")
    implementation("gradle.plugin.at.phatbl:shellexec:$shellExecVersion")
}

println("""
      ========================================================================================================
      Welcome to Gradle version:          ${project.gradle.gradleVersion}
      Java version:                       ${org.gradle.internal.jvm.Jvm.current()}
      Java home:                          ${org.gradle.internal.jvm.Jvm.current().javaHome}
      Gradle user directory is set to:    ${project.gradle.gradleUserHomeDir}
      Project directory:                  ${project.projectDir}
      Running build script:               ${project.buildFile}
      Subprojects:                        ${project.subprojects.map { it.name }}
      """.trimIndent()
       )

//pluginBundle {
//  // These settings are set for the whole plugin bundle
//  website = "http://www.gradle.org/"
//  vcsUrl = "https://github.com/gradle/gradle"
//
//  // tags and description can be set for the whole bundle here, but can also
//  // be set / overridden in the config for specific plugins
//  description = "Greetings from here!"
//
//  // The plugins block can contain multiple plugin entries.
//  //
//  // The name for each plugin block below (greetingsPlugin, goodbyePlugin)
//  // does not affect the plugin configuration, but they need to be unique
//  // for each plugin.
//
//  // Plugin config blocks can set the id, displayName, version, description
//  // and tags for each plugin.
//
//  // id and displayName are mandatory.
//  // If no version is set, the project version will be used.
//  // If no tags or description are set, the tags or description from the
//  // pluginBundle block will be used, but they must be set in one of the
//  // two places.
//
//  (plugins) {
//
//    // first plugin
//    "greetingsPlugin" {
//      // id is captured from java-gradle-plugin configuration
//      displayName = "Gradle Greeting plugin"
//      tags = listOf("individual", "tags", "per", "plugin")
//      version = "1.2"
//    }
//
//    // another plugin
//    "goodbyePlugin" {
//      // id is captured from java-gradle-plugin configuration
//      displayName = "Gradle Goodbye plugin"
//      description = "Override description for this plugin"
//      tags = listOf("different", "for", "this", "one")
//      version = "1.3"
//    }
//  }