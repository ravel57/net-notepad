import com.github.gradle.node.npm.task.NpmTask

plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.0"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.github.node-gradle.node") version "7.1.0"
}

group = "ru.ravel"
version = "0.1"
description = "net-notepad"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

node {
	download = true
	version = "24.22.2"
	nodeProjectDir = file("frontend")
}

// Bundles the editor (CodeMirror 6 + collab + markdown preview) into static/js/app.js
val buildFrontend by tasks.registering(NpmTask::class) {
	dependsOn(tasks.npmInstall)
	args = listOf("run", "build")
	inputs.dir("frontend/src")
	inputs.files("frontend/package.json", "frontend/package-lock.json")
	outputs.dir(layout.buildDirectory.dir("generated/frontend"))
}

sourceSets.main {
	resources.srcDir(buildFrontend)
}

tasks.withType<Test> {
	useJUnitPlatform()
}
