import java.util.LinkedList

plugins {
    id("dev.nx.gradle.project-graph") version("0.1.0")
}

defaultTasks("run")

tasks.register("run") {
    dependsOn(gradle.includedBuild("my-app").task(":app:run"))
}

tasks.register("checkAll") {
    dependsOn(gradle.includedBuild("my-app").task(":app:check"))
    dependsOn(gradle.includedBuild("my-utils").task(":number-utils:check"))
    dependsOn(gradle.includedBuild("my-utils").task(":string-utils:check"))
}


allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
  }

// Define extension functions for changed targets functionality
fun Project.getChangedProjects(): Set<String> {
    val baseRef = "origin/main" // You can customize this
    val gitCommand = "git diff --name-only $baseRef"
    val proc = Runtime.getRuntime().exec(gitCommand)
    proc.waitFor()

    if (proc.exitValue() != 0) {
        println("Error executing git command: ${proc.errorStream.bufferedReader().readText()}")
        return emptySet()
    }

    val changedFiles = proc.inputStream.bufferedReader().readText().trim().split("\n")
    val changedProjects = mutableSetOf<String>()

    // Root project changes affect everything
    if (changedFiles.any { it.startsWith("build.gradle") || it.startsWith("settings.gradle") }) {
        println("Root build files changed - will build all projects")
        return allprojects.map { it.path }.toSet()
    }

    for (file in changedFiles) {
        if (file.isEmpty()) continue

        // Map file path to project
        val segments = file.split("/")
        if (segments.isNotEmpty()) {
            val projectPath = segments[0]
            val projectDir = File(rootDir, projectPath)

            // Check if this is a valid project directory
            if (projectDir.exists() &&
                (File(projectDir, "build.gradle").exists() ||
                        File(projectDir, "build.gradle.kts").exists())) {
                changedProjects.add(projectPath)
            }
        }
    }

    return changedProjects
}

fun Project.buildReverseDependencyGraph(): Map<String, MutableSet<String>> {
    val reverseDependencyGraph = mutableMapOf<String, MutableSet<String>>()

    // Initialize all projects in the graph
    allprojects.forEach { project ->
        reverseDependencyGraph[project.path] = mutableSetOf()
    }

    // Build the reverse dependency graph
    allprojects.forEach { project ->
        project.configurations.filter { it.isCanBeResolved }.forEach { config ->
            try {
                config.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                    val id = artifact.moduleVersion.id

                    // Handle project dependencies within the build
                    if (id.group == rootProject.name) {
                        val dependencyPath = ":${id.name}"
                        reverseDependencyGraph[dependencyPath]?.add(project.path)
                    }
                }
            } catch (e: Exception) {
                // Skip configurations that can't be resolved
                println("Couldn't resolve configuration ${config.name} for ${project.path}: ${e.message}")
            }
        }
    }

    return reverseDependencyGraph
}

fun Project.findAffectedProjects(changedProjects: Set<String>): Set<String> {
    val reverseDependencyGraph = buildReverseDependencyGraph()
    val affectedProjects = changedProjects.toMutableSet()
    val queue = LinkedList(changedProjects)

    while (queue.isNotEmpty()) {
        val current = queue.poll()
        val dependents = reverseDependencyGraph[current]

        dependents?.forEach { dependent ->
            if (!affectedProjects.contains(dependent)) {
                affectedProjects.add(dependent)
                queue.add(dependent)
            }
        }
    }

    return affectedProjects
}

/**
 * Task to analyze changed targets and print them
 */
tasks.register("analyzeChangedTargets") {
    doLast {
        val changedProjects = getChangedProjects()
        println("Changed projects: $changedProjects")

        val affectedProjects = findAffectedProjects(changedProjects)
        println("Affected projects (build targets): $affectedProjects")
    }
}

/**
 * Task to run tests only on affected projects
 */
tasks.register("testChangedTargets") {
    dependsOn("analyzeChangedTargets")

    doLast {
        val changedProjects = getChangedProjects()
        val affectedProjects = findAffectedProjects(changedProjects)

        if (affectedProjects.isEmpty()) {
            println("No affected projects to test")
            return@doLast
        }

        // Create a list of test tasks to run
        val testTasks = affectedProjects.map { projectPath ->
            val projectName = projectPath.substring(1) // Remove leading ':'
            "${projectName}:test"
        }

        // Execute the test tasks
        testTasks.forEach { taskName ->
            println("Running tests for $taskName")
            val includedBuild = gradle.includedBuilds.find { it.name == taskName.split(":")[0] }
            val result = includedBuild
                ?.task(":${taskName.split(":")[1]}")
                ?.result

            if (result?.failure != null) {
                throw GradleException("Tests failed for $taskName: ${result.failure}")
            }
        }
    }
}

/**
 * Task to build only affected projects
 */
tasks.register("buildChangedTargets") {
    dependsOn("analyzeChangedTargets")

    doLast {
        val changedProjects = getChangedProjects()
        val affectedProjects = findAffectedProjects(changedProjects)

        if (affectedProjects.isEmpty()) {
            println("No affected projects to build")
            return@doLast
        }

        // Create a list of build tasks to run
        val buildTasks = affectedProjects.map { projectPath ->
            val projectName = projectPath.substring(1) // Remove leading ':'
            "${projectName}:build"
        }

        // Execute the build tasks
        buildTasks.forEach { taskName ->
            println("Building $taskName")
            val includedBuild = gradle.includedBuilds.find { it.name == taskName.split(":")[0] }
            val result = includedBuild
                ?.task(":${taskName.split(":")[1]}")
                ?.result

            if (result?.failure != null) {
                throw GradleException("Build failed for $taskName: ${result.failure}")
            }
        }
    }
}

// Add a configuration task that can be used to run before other tasks
gradle.taskGraph.whenReady {
    if (project.hasProperty("onlyChanged") && (project.property("onlyChanged") as String).toBoolean()) {
        val changedProjects = getChangedProjects()
        val affectedProjects = findAffectedProjects(changedProjects)

        println("Only building affected projects: $affectedProjects")

        // Disable tasks that aren't for affected projects
        allTasks.forEach { task ->
            if (task.project != rootProject &&
                !affectedProjects.contains(task.project.path)) {
                task.enabled = false
            }
        }
    }
}

// Helper to create a more sophisticated dependency graph that includes included builds
tasks.register("printDependencyGraph") {
    doLast {
        println("Project dependency graph:")

        allprojects.forEach { project ->
            println("${project.path} depends on:")

            project.configurations.filter { it.isCanBeResolved }.forEach { config ->
                try {
                    config.incoming.dependencies.forEach { dependency ->
                        when (dependency) {
                            is org.gradle.api.artifacts.ProjectDependency -> {
                                println("  - ${dependency.dependencyProject.path} (project)")
                            }
                            else -> {
                                if (dependency.group == rootProject.name) {
                                    println("  - ${dependency.group}:${dependency.name}:${dependency.version} (potential included build)")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("  Error resolving ${config.name}: ${e.message}")
                }
            }
        }
    }
}