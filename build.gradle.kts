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

