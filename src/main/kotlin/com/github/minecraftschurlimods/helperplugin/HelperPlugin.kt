package com.github.minecraftschurlimods.helperplugin

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.CoreJavadocOptions
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.kotlin.dsl.*
import java.time.Instant
import java.time.format.DateTimeFormatter


class HelperPlugin : Plugin<Project> {    
    override fun apply(project: Project) {
        with(project) {
            apply<JavaPlugin>()
            apply<MavenPublishPlugin>()
            apply(plugin = "net.neoforged.gradle.userdev")

            val helperExtension = setupExtensions()
            setupPublishing(helperExtension)
            setupJava(helperExtension)
            setupRepositories()
            configureTasks(helperExtension)
            setupArtifacts()
        }
    }

    private fun Project.setupExtensions(): HelperExtension {
        val helperExtension = extensions.create<HelperExtension>("helper")
        group = helperExtension.projectGroup.get()
        version = helperExtension.fullVersion.get()
        base.archivesName.set(helperExtension.projectId)
        return helperExtension
    }

    private fun Project.setupJava(helperExtension: HelperExtension) = java {
        withSourcesJar()
        withJavadocJar()
        toolchain {
            languageVersion.set(helperExtension.java.version)
            vendor.set(helperExtension.java.vendor)
        }
    }

    private fun Project.setupPublishing(helperExtension: HelperExtension) = publishing {
        repositories.maven {
            if (helperExtension.maven.valid.getOrElse(false)) {
                url = helperExtension.maven.url.get()
                credentials {
                    username = helperExtension.maven.user.get()
                    password = helperExtension.maven.password.get()
                }
            } else {
                println("Using repo folder")
                url = uri(layout.buildDirectory.dir("repo"))
            }
        }
        helperExtension.publication = publications.create<MavenPublication>(helperExtension.projectId.get() + "ToMaven") { 
            groupId = helperExtension.projectGroup.get()
            artifactId = helperExtension.projectId.get()
            version = helperExtension.fullVersion.get()
            from(components.getByName("java"))
            pom {
                name.set(this.name)
                url.set(helperExtension.projectUrl)
                packaging = "jar"
                scm {
                    connection.set(helperExtension.gitHub.connection)
                    developerConnection.set(helperExtension.gitHub.developerConnection)
                    url.set(helperExtension.gitHub.url)
                }
                if (helperExtension.gitHub.issuesUrl.isPresent) {
                    issueManagement {
                        system.set("github")
                        url.set(helperExtension.gitHub.issuesUrl)
                    }
                }
                if (helperExtension.gitHub.actionsUrl.isPresent) {
                    ciManagement {
                        system.set("github")
                        url.set(helperExtension.gitHub.actionsUrl)
                    }
                }
                licenses {
                    license {
                        name.set(helperExtension.license.name)
                        url.set(helperExtension.license.url)
                        distribution.set("repo")
                    }
                }
            }
        }
    }

    private fun Project.setupRepositories() = repositories {
        mavenLocal()
        mavenCentral()
        maven {
            name = "Minecraftschurli Maven"
            url = uri("https://minecraftschurli.ddns.net/repository/maven-public")
        }
    }

    private fun Project.configureTasks(helperExtension: HelperExtension) = tasks {
        createSetupGitHubActionsTask(helperExtension)
        withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
        }
        named<Javadoc>("javadoc").configure {
            options.encoding = "UTF-8"
            (options as CoreJavadocOptions).addStringOption("Xdoclint:all,-missing", "-public")
            (options as StandardJavadocDocletOptions).tags = listOf(
                "side:a:Side:",
                "apiNote:a:API Note:",
                "implSpec:a:Implementation Requirements:",
                "implNote:a:Implementation Note:"
            )
            if (JavaVersion.current().isJava9Compatible) {
                (options as CoreJavadocOptions).addBooleanOption("html5", true)
            }
        }
        withType<Jar>().configureEach {
            from(helperExtension.license.file)
            val extension = archiveClassifier.map { if (it.isNotEmpty()) "-$it" else "" }.getOrElse("")
            manifest.attributes(
                "Maven-Artifact"         to helperExtension.artifactLocator.get(),
                "Specification-Title"    to helperExtension.projectId.get(),
                "Specification-Vendor"   to helperExtension.projectVendor.get(),
                "Specification-Version"  to "1",
                "Implementation-Title"   to helperExtension.projectId.get() + extension,
                "Implementation-Version" to helperExtension.projectVersion.get(),
                "Implementation-Vendor"  to helperExtension.projectVendor.get(),
                "Built-On-Java"          to "${System.getProperty("java.vm.version")} (${System.getProperty("java.vm.vendor")})",
                "Built-On-Minecraft"     to helperExtension.minecraftVersion.get(),
                "Built-On-NeoForge"      to helperExtension.neoVersion.get(),
                "Timestamp"              to DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                "FMLModType"             to helperExtension.projectType.map { it.modType }.get(),
                "LICENSE"                to helperExtension.license.name.get()
            )
        }
        processResources {
            val files: FileCollection = project.fileTree("dir" to destinationDir.path, "include" to "**/*.json")
            doLast {
                files.forEach {
                    it.writeText(JsonOutput.toJson(JsonSlurper().parse(it)))
                }
            }
        }
        if (helperExtension.projectType.get() == HelperExtension.Type.MOD) {
            val generateModsToml = register<GenerateModsTomlTask>("generateModsToml") {
                val modproperties = helperExtension.modproperties.orNull
                val dependencies: List<Dependency>? = helperExtension.dependencies.orNull
                val mcPublish = if (helperExtension.mcPublish.curseforge.isPresent || helperExtension.mcPublish.modrinth.isPresent) {
                    McPublish(
                        helperExtension.mcPublish.modrinth.orNull,
                        helperExtension.mcPublish.curseforge.orNull
                    )
                } else null
                val projectId = helperExtension.projectId.get()

                modsToml.set(ModsToml(
                    helperExtension.loader.name.get(),
                    helperExtension.loader.version.get(),
                    helperExtension.license.name.get(),
                    listOf(Mod(
                        projectId,
                        helperExtension.projectVersion.get(),
                        helperExtension.projectName.get(),
                        helperExtension.projectUrl.get(),
                        helperExtension.projectAuthors.get(),
                        helperExtension.projectDescription.get()
                    )),
                    mcPublish,
                    if (dependencies != null) mapOf(projectId to dependencies) else null,
                    if (!modproperties.isNullOrEmpty()) mapOf(projectId to modproperties) else null
                ))
            }
            processResources {
                from(generateModsToml) {
                    into("META-INF/")
                }
            }
        }
    }

    private fun TaskContainer.createSetupGitHubActionsTask(helperExtension: HelperExtension) {
        val githubOutputFile = System.getenv("GITHUB_OUTPUT") ?: return
        register<WriteGitHubActionsOutputTask>("setupGithubActions") {
            outputFile.set(project.file(githubOutputFile))
            values.put("modid", helperExtension.projectId)
            values.put("version", helperExtension.fullVersion)
            values.put("minecraft_version", helperExtension.minecraftVersion)
        }
    }

    private fun Project.setupArtifacts() = artifacts {
        add("archives", tasks.named<Jar>("jar"))
        add("archives", tasks.named<Jar>("sourcesJar"))
        add("archives", tasks.named<Jar>("javadocJar"))
    }
}

