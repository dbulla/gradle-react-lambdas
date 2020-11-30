# Gradle React-Lambdas Plugin

## The problem:

I recently had to work with a project that was a mash-up of a React front-end, combined with multiple AWS Lambdas.

While all the code was JavaScript/TypeScript, there were large differences between the React build and the lambda builds - and even within the lambdas (up to 20 in a repo), 
Typescript was used in most, but not all of the projects.


## Constraints

I was somewhat constrained in that I could not change the project's code to bring them all into a single framework.  We had different toolsets, 
different frameworks, etc., so I really couldn't take advantage of some of the existing JS tools which might help.

Our project structure looked like this:
```text
react 
  src
    etc
src 
  lambda 
    lambda_1 
    lambda_2
    .
    .
    lambda_18
```

All of the lambdas code is in a `src/lambdas` dir, and if there's a front-end component, that goes into a `react` dir as shown.

## Doing everything in Jenkins

I initially wrote a bunch of Jenkins pipeline code to manually iterate through all the projects when linting, testing, etc - but I had to keep track
of what passed/failed for each subproject, and then notify Jenkins only after all the subprojects had been run.  Needless to say, there was a lot of boilerplate 
code which was telling the proejct _how_ to do the build steps, not _what_ build steps to do.

It finally dawned on me that Gradle handles all of this quite easily - it knows how to handle a multiple project repo, and run steps on ALL
the subprojects before reporting failure - stuff I'd been trying to implement.  

Moving the logic out of Jenkins meant that the Jenkins code got a lot clearer - plus, I could also use the Gradle script to run the build steps locally to duplicate the pipeline.
Doing pipeline development locally is much, much faster than committing and watching the pipeline, so this is a big deal for me.  Plus, we can use the same
Docker container the pipeline uses.

Now, Gradle isn't a "native" JS tool.  But, it doesn't need to be.  For this use case, I'm just calling shell scripts which run the JS commands needed.


## The Solution: Creating a Gradle Multi-Project for the Monorepo

The first step in this is telling Gradle what the subprojects are. As I already mentioned, my directory setup looked like this:

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

include("lambda_1")
project(":lambda_1").projectDir = File("src/lambda/lambda_1")

include("lambda_2")
project(":lambda_2").projectDir = File("src/lambda/lambda_2")

include("lambda_18")
project(":lambda_18").projectDir = File("src/lambda/lambda_18")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
    }
} 
```

## Creating the build script the project - first pass

Here's what my first build script looked like: [build.gradle.kts](https://github.com/dbulla/gradle-react-lambdas/blob/gh-pages/src/exampleFullScript/build.gradle.kts)

Breaking it down, we have some plugins that we bring in.  I like the `shellexec` plugin, `task-tree` and `versions` are often very helpful.

```kotlin
import at.phatbl.shellexec.ShellExec

plugins {
    id("at.phatbl.shellexec") version "1.5.2"
    id("com.dorongold.task-tree") version "1.5"
    id("com.github.ben-manes.versions") version "0.36.0"
}
dependencies{}
```

Next, we hve some simple methods used to determine what type of subproject is being run, and print out a banner. 

```kotlin
    private val reactEnvironment = System.getProperty("REACT_APP_ENVIRONMENT") ?: "local"
    private fun packageJsonExists(project: Project): Boolean = File(project.projectDir, "package.json").exists()
    private fun isLambda(project: Project): Boolean = project.projectDir.toString().contains("src/lambda")
    private fun isReact(project: Project): Boolean = project.projectDir.toString().contains("react")
    private val reactEnvironment = System.getProperty("REACT_APP_ENVIRONMENT") ?: "local"

    println("""
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
       )

```


Now, we specify tasks for subprojects.  
- The `command` which will be executed differs between the React projects (npm), and AWS Lambdas (yarn).  Note that with the use of `when`, we can easily extend the types of commands if needed
- The `onlyIf` closure determines if this task should be run on the given subproject (that's why there's the `this.project` instead of just `project`)
```kotlin
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
            commandLine = "ls -l serverless.yaml event.json package-lock.json package.json serverless.yaml jest-config.js test *.txt *.ts yarn.lock jest.config.js".split( " " )
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
 

```

Our pipeline tools need a single report for tests and test coverage, so we've got to merge a bunch of stuff.  We ended up using a couple of tools:
- lcov-result-merger
- junit-merge
- junit2html

I but this all into a method called `createMergeCommands.`

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

```

If lambdas are added or renamed, the `settings.gradle.kts` file would need to be modified.  I'm really lazy, so I wrote a task to 
autogenerate `settings.gradle.kts` based on the repository structure:

```kotlin
    tasks.register("regenerateSettingsDotGradle") {

    description = "Utility class to regenerate the tools/settings.gradle.kts file - if you create more lambdas, run this"
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
Finally, a nice util task to output all the version fo the tools used:
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
```

This worked quite nicely.  However, as the number of projects grew, there was a lot of copy/paste across the projects. 



##Creating the build script the project - second pass - use a plugin

I didn't want to deal with a bunch of copy/pasted code, so I wrote a Gradle plugin to handle the work - the
individual projects' `build.gradle.kts` scripts were this simple (5 lines!):

```kotlin
group = "net.something.someone"
version = "1.0.0"
plugins {
    id("net.something.someone.react-lambdas") version "0.0.1-SNAPSHOT"
}
```

## Creating the plugin to manage the project

### Plugin class - ReactLambdasPluginExtension

The plugin itself is quite simple - we have several nice plugins I like to add to every project, and then the creation of the React/AWS
Lambda processing tasks. 

Tthe code below is listed in it's entirety here: [ReactLambdasPluginExtension,kt](https://github.com/dbulla/gradle-react-lambdas/blob/initialWork/src/main/kotlin/com/nurflugel/gradle/plugins/reactlambdas/ReactLambdasPlugin.kt).  As you can see, it very closely parallels the build script

For the first bit of the plugin class, we do some setup and boilerplate:

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

Next, we apply the plugin itself, and it's extension.  We also apply several very useful plugins which our projects now don't have to
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
Now for the meat - we want to specify some tasks that are are only run in the subprojects, and some that rae global, and some of them should 
only in react or lambda subprojects. 

Again, this is almost exactly the same as the code from the old `build.gradle.kts`:

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

## Docker image

To make the pipeline faster, I preloaded all of the tools into the Docker image, including Gradle.  Then, the tasks were listed and that downloaded the dependencies into the Gradle cache, which also went into the Docker image.  This way Gradle didn't need to download or install anything when it started up.  

