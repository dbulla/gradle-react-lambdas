# Gradle React-Lambdas Plugin

## The problem:

I recently had to work with a project that was a mash-up of a React front-end, combined with multiple AWS Lambdas.

While all the code was JavaScript/TypeScript, there were large differences between the React build and the lambda builds - different
toolsets, different frameworks, etc. I was somewhat constrained in that I could not change the code to bring them all into a single
framework, so I really couldn't take advantage of some of the existing JS tools.

Instead, I wrote a bunch of Jenkins code to manually iterate through all the projects when linting, testing, etc - but I had to keep track
of what passed/failed, and notify Jenkins only after all of the subprojects had been run.

It finally dawned on me that Gradle handles all of this quite easily - it knows how to handle a multiple project repo, and run tests on ALL
the subprojects before reporting failure - stuff I'd been trying to implement.

Creating a Gradle Project for the Monorepo

The first step in this is telling Gradle what the subprojects are. In my case, my directory setup looked like this:

react src lambda lambda1 lambda2 . . .

You tell Gradle about subproject configuration in the settings.gradle.kts file. Mine looked like this:

rootProject.name = "some-multiproject"

include("react")
project(":react").projectDir = File("react")

include("authorizer")
project(":authorizer").projectDir = File("src/lambda/authorizer")

pluginManagement { repositories { gradlePluginPortal()
mavenLocal()
companyPluginRepo()
} }

Creating the build script the project

I didn't want each project I had to work with to have a bunch of copy/pasted build code, so I wrote a Gradle plugin to handle the work - the
individual projects' build.gradle.kts scripts were this simple:

group = "net.something.someone"
version = "1.0.0"
plugins { id("net.something.someone.react-lambdas") version "0.0.1-SNAPSHOT"
} dependencies {}

Creating the plugin to manage the project

The plugin itself is quite simple - we have several nice plugins I like to add to every project, and then the creation of the React/AWS
Lambda processing tasks:



