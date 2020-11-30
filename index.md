# Gradle React-Lambdas Plugin

## The problem:

I recently had to work with a project that was a mash-up of a React front-end, combined with multiple AWS Lambdas.

While all the code was JavaScript/TypeScript, there were large differences between the React build and the lambda builds - 

### Constraints

I was somewhat constrained in that I could not change the code to bring them all into a single framework.  We had different toolsets, 
different frameworks, etc., so I really couldn't take advantage of some of the existing JS tools which might help.

Instead, I wrote a bunch of Jenkins code to manually iterate through all the projects when linting, testing, etc - but I had to keep track
of what passed/failed, and notify Jenkins only after all of the subprojects had been run.

It finally dawned on me that Gradle handles all of this quite easily - it knows how to handle a multiple project repo, and run tests on ALL
the subprojects before reporting failure - stuff I'd been trying to implement.

Gradle isn't a "native" JS tool, but for this use case I'm just calling shell scripts which run the JS commands needed.


## The Solution: Creating a Gradle Multi-Project for the Monorepo

The first step in this is telling Gradle what the subprojects are. In my case, my directory setup looked like this:

```text
react 
  src
    etc
src 
  lambda 
    lambda1 
    lambda2
    .
    .
    .
```

You tell Gradle about subproject configuration in the settings.gradle.kts file. Mine looked like this:

```kotlin
rootProject.name = "some-multiproject"

include("react")
project(":react").projectDir = File("react")

include("lambda1")
project(":lambda1").projectDir = File("src/lambda/lambda1")

include("lambda2")
project(":lambda2").projectDir = File("src/lambda/lambda2")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        realPluginRepo() // our corporate repo where the plugin is published to
    }
} 
```

## Creating the build script the project - first pass

Reference: [a relative link](dibble.md)

<details>
<summary>Click to expand...</summary>

```kotlin
import at.phatbl.shellexec.ShellExec

group = "net.something.someone"
version = "1.0.0"
plugins {
    id("at.phatbl.shellexec") version "1.5.2"
    id("com.dorongold.task-tree") version "1.5"
}
dependencies{}
```

</details>  





```
    val reactEnvironment = System.getProperty("REACT_APP_ENVIRONMENT") ?: "local"
    val prefix = """
             ========================================================================================================
             Welcome to Gradle version:          ${project.gradle.gradleVersion}
             Java version:                       ${Jvm.current()}
             Java home:                          ${Jvm.current().javaHome}
             Gradle user directory is set to:    ${project.gradle.gradleUserHomeDir}
             Project directory:                  ${project.projectDir}
             Running build script:               ${project.buildFile}
             ReactEnvironment:                   $reactEnvironment
             Subprojects:                        ${project.subprojects.map { it.name }}
             """.trimIndent()
    println(prefix)

    private fun packageJsonExists(project: Project): Boolean = File(project.projectDir, "package.json").exists()
    private fun isLambda(project: Project): Boolean = project.projectDir.toString().contains("src/lambda")
    private fun isReact(project: Project): Boolean = project.projectDir.toString().contains("react")
    private val reactEnvironment = System.getProperty("REACT_APP_ENVIRONMENT") ?: "local"

    
    /** Everything in subprojects applies only to the sub-projects - not the global project */
    subprojects {
        tasks {
            tasks.register<ShellExec>("install") {
                description = "Do the 'install' operation"
                group = "React/Lambdas"
                command = when {
                    isReact(this.project) -> ext.installReactCommand ?: "npm install"
                    else                  -> ext.installLambdaCommand ?: "yarn"
                }
                onlyIf { packageJsonExists(this.project) } // using this.project gets the current subproject - not the global project
    
            }
            tasks.register<ShellExec>("installProduction") {
                description = "Package the production dependencies.  Run it like this: 'gradle installProduction -DREACT_APP_ENVIRONMENT=dev"
                group = "React/Lambdas"
                command = when {
                    isReact(this.project) -> ext.installReactProductionCommand ?: "npm run build"
                    else                  -> ext.installLambdaProductionCommand ?: "yarn --production"
                }
                onlyIf { packageJsonExists(this.project) }
                doLast { println("The env used is: $reactEnvironment") }
            }
            tasks.register<ShellExec>("test") {
                description = "Run the tests for both React and Lambdas"
                group = "React/Lambdas"
                command = when {
                    isReact(this.project) -> ext.testReactCommand ?: "npm run test-coverage"
                    else                  -> ext.testLambdaCommand ?: "yarn test --testTimeout=10000"
                }
                onlyIf { packageJsonExists(this.project) }
            }
    
            tasks.register<ShellExec>("lint") {
                description = "Lint the code"
                group = "React/Lambdas"
                command = when {
                    isReact(this.project) -> ext.lintReactCommand ?: "npm run lint-ts"
                    else                  -> ext.lintLambdaCommand ?: "yarn run lint"
                }
                onlyIf { packageJsonExists(this.project) && (file(".eslintrc").exists() || file(".eslintrc.js").exists()) }
            }
    
            tasks.register<ShellExec>("tsc") {
                description = "Run tsc on any project that has a tsconfig.json "
                group = "React/Lambdas"
                command = "yarn run tsc"
                onlyIf {
                    val lambda = isLambda(this.project)
                    val exists = file("tsconfig.json").exists()
                    println("tsconfig.json exists = $exists")
                    lambda && exists
                }
            }
    
            // React only
            tasks.register<ShellExec>("runReact") {
                description = "Run the React app like this: 'gradle runReact -DREACT_APP_ENVIRONMENT=dev'.  If -DREACT_APP_ENVIRONMENT is omitted, local is used"
                group = "React/Lambdas"
                command = ext.runReactCommand ?: "npm run start-$reactEnvironment"
                onlyIf { isReact(this.project) }
            }
    
            // React only
            tasks.register<ShellExec>("buildCss") {
                description = "Build the CSS"
                group = "React/Lambdas"
                command = ext.buildCssCommand ?: "npm run build-css"
                onlyIf { isReact(this.project) }
            }
    
            tasks.register<Exec>("cleanUpForDeploy") {
                description = "Remove any files in the lambda dirs not needed for deployment - this WILL delete source-controlled files, so run locally with care!"
                group = "React/Lambdas"
                isIgnoreExitValue = true
                commandLine = "ls -l serverless.yaml event.json package-lock.json package.json serverless.yaml jest-config.js test *.txt *.ts yarn.lock jest.config.js".split(
                        " "
                                                                                                                                                                             )
                onlyIf { isLambda(this.project) && packageJsonExists(this.project) }
            }
    
            // below are housekeeping tasks
            tasks.register<ShellExec>("createModulesList") {
                description = "create a list of the contents for each module and export that into a file - very useful for fast comparisons of different builds"
                group = "React/Lambdas Housekeeping"
                command = "ls -w1 node_modules > node_modules.out"
                onlyIf { packageJsonExists(this.project) }
            }
    
            tasks.register<Delete>("cleanNodeModules") {
                description = "Clean the node modules dirs"
                group = "React/Lambdas Housekeeping"
                delete = setOf("node_modules", "node_modules.out")
            }
        }
    }
    
    tasks.register<ShellExec>("mergeTestResults") {
        description = "Merge all the test results"
        group = "React/Lambdas"
        command = """
            mkdir -p ../lcov
            """ + createMergeCommands(project)
    }




/** Some of the tools don't give you an option to ignore stuff that's not there and fail, so we have to have different
 * commands for whether or not React is there.  It's assumed that there is at least one lambda in the project; else, we'll
 * have to have a third condition of react w/no lambdas.
 */
private fun createMergeCommands(project: Project): String {
    val hasReact = File("${project.projectDir}/..")
            .listFiles()
            .any { it.name == "react" }

    println("hasReact files is $hasReact")
    return when {
        hasReact -> {
            """
                        #  First, copy results from the React dir into the lcov dir - OK if it's not there
                lcov-result-merger '../react/coverage/lcov.info' '../lcov/react_lcov.info' || true
                        #  Next, merge any lambdas into the lcov dir
                lcov-result-merger '../src/lambda/*/coverage/lcov.info' '../lcov/lambdas_lcov.info'
                        #  Finally, merge it all together so we have SINGLE file we can send to QMA
                lcov-result-merger '../lcov/*_lcov.info' '../lcov/results-lcov.info' || true
            
                        # Next, run the command to merge all the Junit xml files into one toplevel xml file - https://www.npmjs.com/package/junit-merge
                junit-merge  ../src/lambda/*/coverage/jest/junit.xml -o ../allLambdasMergedTests.xml || true
                junit-merge  ../react/coverage/jest/junit.xml  -o ../allReactMergedTests.xml || true
                junit-merge  ../allLambdasMergedTests.xml ../allReactMergedTests.xml -o ../allMergedTests.xml || true
            
                 #  Finally, create an HTML report from THAT via junit2html - https://github.com/inorton/junit2html
                 junit2html ../allMergedTests.xml ../allMergedTests.html
"""
        }
        else     -> { // React is missing, so these commands need to be adjusted
            """
                        #  Merge any lambdas into the lcov dir
                lcov-result-merger '../src/lambda/*/coverage/lcov.info' '../lcov/lambdas_lcov.info'
                        #  Finally, merge it all together so we have SINGLE file we can send to QMA
                lcov-result-merger '../lcov/*_lcov.info' '../lcov/results-lcov.info' || true
            
                        # Next, run the command to merge all the Junit xml files into one toplevel xml file - https://www.npmjs.com/package/junit-merge
            
                junit-merge  ../src/lambda/*/coverage/jest/junit.xml -o ../allLambdasMergedTests.xml || true
                junit-merge  ../allLambdasMergedTests.xml -o ../allMergedTests.xml || true
            
                 #  Finally, create an HTML report from THAT via junit2html - https://github.com/inorton/junit2html
                 junit2html ../allMergedTests.xml ../allMergedTests.html
"""
        }
    }
}
    tasks.register("regenerateSettingsDotGradle") {

    description = "Utility class to regenerate the tools/settings.gradle.kts file - if you create more lambdas, run this "
    group = "React/Lambdas Housekeeping"
    doLast {
        // create the file header
        val stringBuilder = StringBuilder("""rootProject.name = "${rootProject.name}"
"""
                                         )
        // deal with the react contents if they exist
        val reactDir = File("react")
        println("React dir exists: ${reactDir.exists()}")
        if (reactDir.exists()) {
            stringBuilder.append("""

include("react")
project(":react").projectDir = File("../react")
"""
                                )
        }

        // Now create a mapping entry for each lambda
        val lambdasDir = File("src/lambda")
        if (lambdasDir.exists()) {
            lambdasDir.listFiles()
                    .filter {
                        val hasPackageJson = it.listFiles()?.map { file -> file.name }?.any { name -> name == "package.json" }
                                             ?: false
                        hasPackageJson
                    }
                    .map { it.name }
                    .sorted()
                    .map {
                        """
include("$it")
project(":$it").projectDir = File("../src/lambda/$it")
"""
                    }
                    .forEach { stringBuilder.append(it) }
        }

        // finally, add the plugin repo settings
        stringBuilder.append("""

            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenLocal()
                    corporatePluginRepo()
                }
            } 
        """.trimIndent()
                            )

        // write it to disk
        val fileContents = stringBuilder.toString()
        File("settings.gradle.kts").writeText(fileContents)

    }
}

tasks.register<ShellExec>("outputToolVersions") {
    description = "Show the tool versions"
    group = "React/Lambdas Housekeeping"

    command = """
        echo "Git version " $(git --version)
        echo "NPM version " $(npm --version)
        echo "Node version" $(node --version)
        echo "Yarn version" $(yarn --version)
        echo "Tsc version " $(tsc --version)
        echo "pip3 version " $(pip3 -V)
        echo "junit-merge version " $(junit-merge -V)
        ${ext.outputToolVersionsExtra ?: ""}
        """ + createMergeCommands(project)
}
```


## Creating the build script the project - second pass - use a plugin

I didn't want each project I had to work with to have a bunch of copy/pasted build code, so I wrote a Gradle plugin to handle the work - the
individual projects' build.gradle.kts scripts were this simple:

```kotlin
group = "net.something.someone"
version = "1.0.0"
plugins {
    id("net.something.someone.react-lambdas") version "0.0.1-SNAPSHOT"
}
```

## Creating the plugin to manage the project

The plugin itself is quite simple - we have several nice plugins I like to add to every project, and then the creation of the React/AWS
Lambda processing tasks. For the first bit of the plugin class, we do some setup and boilerplate:

```kotlin
package net.something.gradle.plugins.reactlambdas

import at.phatbl.shellexec.ShellExec
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.internal.jvm.Jvm
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.register
import java.io.File

class ReactLambdasPlugin : Plugin<Project> {

    private fun packageJsonExists(project: Project): Boolean = File(project.projectDir, "package.json").exists()
    private fun isLambda(project: Project): Boolean = project.projectDir.toString().contains("src/lambda")
    private fun isReact(project: Project): Boolean = project.projectDir.toString().contains("react")
    private val reactEnvironment = System.getProperty("REACT_APP_ENVIRONMENT") ?: "local"
```

We sniff the project repo - is a subproject a `react` dir? A lambdas under `src/lambda`? this is how we tell Gradle to handle the different
types of projects. Note that this can easily be extended to handle other types of subprojects.

Next, we apply the plugin itself, and it's extension, and we also apply several very useful plugins which our projects now don't have to
import:

```kotlin
override fun apply(project: Project) {
    // register plugin extension
    val ext = project.extensions.create("reactLambdas", ReactLambdasPluginExtension::class, project)
    applyPlugins(project)
    registerTasks(project, ext)
}

/** Apply the specified plugins to the project */
private fun applyPlugins(project: Project) = with(project) {
    pluginManager.apply("idea")
    pluginManager.apply("project-report")
    pluginManager.apply("com.github.ben-manes.versions")
    pluginManager.apply("com.dorongold.task-tree")
    pluginManager.apply("at.phatbl.shellexec")
}
```
I like the `shellexec` package over the default Gradle `exec` task, as I don't need to split the command line up to use it.

Now for the meat - we want to specify some tasks that are are only run in the subprojects, and some that rae global, and some of them should 
only in react or lambda subprojects. 

Those helper methods shown above help keep the real logic tidy. 

Notice that we use the `onlyIf` predicate to determine if we should do this at all - for instance, in the `install` task, we will 
skip the dir if there is no `package.json` file in it. Sometimes devs add new
dirs into the source trees, and we don't want to process anything that really shouldn't be.

For the shell `command`, we provide overridable defaults for the React and AWS Lambda dirs (more on overriding these later).

```kotlin
private fun registerTasks(project: Project, ext: ReactLambdasPluginExtension) = with(project) {

/** Everything in subprojects applies only to the sub-projects - not the global project */
subprojects {
    tasks {
        tasks.register<ShellExec>("install") {
            description = "Do the 'install' operation"
            group = "React/Lambdas"
            command = when {
                isReact(this.project) -> ext.installReactCommand ?: "npm install"
                else                  -> ext.installLambdaCommand ?: "yarn"
            }
            onlyIf { packageJsonExists(this.project) } // using this.project gets the current subproject - not the global project

        }
        tasks.register<ShellExec>("installProduction") {
            description = "Package the production dependencies.  Run it like this: 'gradle installProduction -DREACT_APP_ENVIRONMENT=dev"
            group = "React/Lambdas"
            command = when {
                isReact(this.project) -> ext.installReactProductionCommand ?: "npm run build"
                else                  -> ext.installLambdaProductionCommand ?: "yarn --production"
            }
            onlyIf { packageJsonExists(this.project) }
            doLast { println("The env used is: $reactEnvironment") }
        }
        tasks.register<ShellExec>("test") {
            description = "Run the tests for both React and Lambdas"
            group = "React/Lambdas"
            command = when {
                isReact(this.project) -> ext.testReactCommand ?: "npm run test-coverage"
                else                  -> ext.testLambdaCommand ?: "yarn test --testTimeout=10000"
            }
            onlyIf { packageJsonExists(this.project) }
        }

        tasks.register<ShellExec>("lint") {
            description = "Lint the code"
            group = "React/Lambdas"
            command = when {
                isReact(this.project) -> ext.lintReactCommand ?: "npm run lint-ts"
                else                  -> ext.lintLambdaCommand ?: "yarn run lint"
            }
            onlyIf { packageJsonExists(this.project) && (file(".eslintrc").exists() || file(".eslintrc.js").exists()) }
        }

        tasks.register<ShellExec>("tsc") {
            description = "Run tsc on any project that has a tsconfig.json "
            group = "React/Lambdas"
            command = "yarn run tsc"
            onlyIf {
                val lambda = isLambda(this.project)
                val exists = file("tsconfig.json").exists()
                println("tsconfig.json exists = $exists")
                lambda && exists
            }
        }

        // React only
        tasks.register<ShellExec>("genGraphQlSchema") {
            description = "Generate the GraphQA schema config file."
            group = "React/Lambdas"
            command = ext.genGraphQlSchemaCommand
                      ?: "node --unhandled-rejections=strict src/scripts/graphqlSchemaIntrospection.js https://api.dev.digitalassets.nike.net/graphql"
            onlyIf { isReact(this.project) }
        }

        // React only
        tasks.register<ShellExec>("runReact") {
            description = "Run the React app like this: 'gradle runReact -DREACT_APP_ENVIRONMENT=dev'.  If -DREACT_APP_ENVIRONMENT is omitted, local is used"
            group = "React/Lambdas"
            command = ext.runReactCommand ?: "npm run start-$reactEnvironment"
            onlyIf { isReact(this.project) }
        }

        // React only
        tasks.register<ShellExec>("buildCss") {
            description = "Build the CSS"
            group = "React/Lambdas"
            command = ext.buildCssCommand ?: "npm run build-css"
            onlyIf { isReact(this.project) }
        }

        tasks.register<Exec>("cleanUpForDeploy") {
            description = "Remove any files in the lambda dirs not needed for deployment - this WILL delete source-controlled files, so run locally with care!"
            group = "React/Lambdas"
            isIgnoreExitValue = true
            commandLine = "ls -l serverless.yaml event.json package-lock.json package.json serverless.yaml jest-config.js test *.txt *.ts yarn.lock jest.config.js".split(
                    " "
                                                                                                                                                                         )
            onlyIf { isLambda(this.project) && packageJsonExists(this.project) }
        }

        // below are housekeeping tasks
        tasks.register<ShellExec>("createModulesList") {
            description = "create a list of the contents for each module and export that into a file - very useful for fast comparisons of different builds"
            group = "React/Lambdas Housekeeping"
            command = "ls -w1 node_modules > node_modules.out"
            onlyIf { packageJsonExists(this.project) }
        }

        tasks.register<Delete>("cleanNodeModules") {
            description = "Clean the node modules dirs"
            group = "React/Lambdas Housekeeping"
            delete = setOf("node_modules", "node_modules.out")
        }
    }
}
}

```

Next, we have to merge our test results into a single file that our pipeline can publish, rather than many smaller ones (corporate
requirement - one report per repo):

```kotlin
    tasks.register<ShellExec>("mergeTestResults") {
    description = "Merge all the test results"
    group = "React/Lambdas"
    command = """
        mkdir -p ../lcov
        """ + createMergeCommands(project)
}


/** Some of the tools don't give you an option to ignore stuff that's not there and fail, so we have to have different
 * commands for whether or not React is there.  It's assumed that there is at least one lambda in the project; else, we'll
 * have to have a third condition of react w/no lambdas.
 */
private fun createMergeCommands(project: Project): String {
    val hasReact = File("${project.projectDir}/..")
            .listFiles()
            .any { it.name == "react" }

    println("hasReact files is $hasReact")
    return when {
        hasReact -> {
            """
                        #  First, copy results from the React dir into the lcov dir - OK if it's not there
                lcov-result-merger '../react/coverage/lcov.info' '../lcov/react_lcov.info' || true
                        #  Next, merge any lambdas into the lcov dir
                lcov-result-merger '../src/lambda/*/coverage/lcov.info' '../lcov/lambdas_lcov.info'
                        #  Finally, merge it all together so we have SINGLE file we can send to QMA
                lcov-result-merger '../lcov/*_lcov.info' '../lcov/results-lcov.info' || true
            
                        # Next, run the command to merge all the Junit xml files into one toplevel xml file - https://www.npmjs.com/package/junit-merge
                junit-merge  ../src/lambda/*/coverage/jest/junit.xml -o ../allLambdasMergedTests.xml || true
                junit-merge  ../react/coverage/jest/junit.xml  -o ../allReactMergedTests.xml || true
                junit-merge  ../allLambdasMergedTests.xml ../allReactMergedTests.xml -o ../allMergedTests.xml || true
            
                 #  Finally, create an HTML report from THAT via junit2html - https://github.com/inorton/junit2html
                 junit2html ../allMergedTests.xml ../allMergedTests.html
"""
        }
        else     -> { // React is missing, so these commands need to be adjusted
            """
                        #  Merge any lambdas into the lcov dir
                lcov-result-merger '../src/lambda/*/coverage/lcov.info' '../lcov/lambdas_lcov.info'
                        #  Finally, merge it all together so we have SINGLE file we can send to QMA
                lcov-result-merger '../lcov/*_lcov.info' '../lcov/results-lcov.info' || true
            
                        # Next, run the command to merge all the Junit xml files into one toplevel xml file - https://www.npmjs.com/package/junit-merge
            
                junit-merge  ../src/lambda/*/coverage/jest/junit.xml -o ../allLambdasMergedTests.xml || true
                junit-merge  ../allLambdasMergedTests.xml -o ../allMergedTests.xml || true
            
                 #  Finally, create an HTML report from THAT via junit2html - https://github.com/inorton/junit2html
                 junit2html ../allMergedTests.xml ../allMergedTests.html
"""
        }
    }
}
}
```

I hate maintaining files needed for the build - this next task will create the `settings.gradle.kts` file:

```kotlin
    tasks.register("regenerateSettingsDotGradle") {

    description = "Utility class to regenerate the tools/settings.gradle.kts file - if you create more lambdas, run this "
    group = "React/Lambdas Housekeeping"
    doLast {
        // create the file header
        val stringBuilder = StringBuilder("""rootProject.name = "${rootProject.name}"
"""
                                         )
        // deal with the react contents if they exist
        val reactDir = File("react")
        println("React dir exists: ${reactDir.exists()}")
        if (reactDir.exists()) {
            stringBuilder.append("""

include("react")
project(":react").projectDir = File("../react")
"""
                                )
        }

        // Now create a mapping entry for each lambda
        val lambdasDir = File("src/lambda")
        if (lambdasDir.exists()) {
            lambdasDir.listFiles()
                    .filter {
                        val hasPackageJson = it.listFiles()?.map { file -> file.name }?.any { name -> name == "package.json" }
                                             ?: false
                        hasPackageJson
                    }
                    .map { it.name }
                    .sorted()
                    .map {
                        """
include("$it")
project(":$it").projectDir = File("../src/lambda/$it")
"""
                    }
                    .forEach { stringBuilder.append(it) }
        }

        // finally, add the plugin repo settings
        stringBuilder.append("""

            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenLocal()
                    corporatePluginRepo()
                }
            } 
        """.trimIndent()
                            )

        // write it to disk
        val fileContents = stringBuilder.toString()
        File("settings.gradle.kts").writeText(fileContents)

    }
}
```

And, finally, a nice util function to print out all the versions being used. We let the extensions add any extra commands. Lastly, we print
a banner after all the project evaluation has been done.

```kotlin

tasks.register<ShellExec>("outputToolVersions") {
    description = "Show the tool versions"
    group = "React/Lambdas Housekeeping"

    command = """
        echo "Git version " $(git --version)
        echo "NPM version " $(npm --version)
        echo "Node version" $(node --version)
        echo "Yarn version" $(yarn --version)
        echo "Tsc version " $(tsc --version)
        echo "pip3 version " $(pip3 -V)
        echo "junit-merge version " $(junit-merge -V)
        ${ext.outputToolVersionsExtra ?: ""}
        """ + createMergeCommands(project)
}

afterEvaluate {
    // we print the banner here as it needs to be done AFTER evaluation so the vars can be read in
    val reactEnvironment = System.getProperty("REACT_APP_ENVIRONMENT") ?: "local"
    val prefix = """
             ========================================================================================================
             Welcome to Gradle version:          ${project.gradle.gradleVersion}
             Java version:                       ${Jvm.current()}
             Java home:                          ${Jvm.current().javaHome}
             Gradle user directory is set to:    ${project.gradle.gradleUserHomeDir}
             Project directory:                  ${project.projectDir}
             Running build script:               ${project.buildFile}
             ReactEnvironment:                   $reactEnvironment
             Subprojects:                        ${project.subprojects.map { it.name }}
             """.trimIndent()
    println(prefix)
}
```



