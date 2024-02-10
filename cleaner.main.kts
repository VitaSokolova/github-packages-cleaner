@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
@file:DependsOn("com.github.kittinunf.fuel:fuel-jvm:3.0.0-alpha1")
@file:DependsOn("com.google.code.gson:gson:2.10.1")

import com.google.gson.JsonParser
import fuel.Fuel
import fuel.Request
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.Period
import java.time.format.DateTimeFormatter

val token = "ghp_xxx"
val organisation = "your organisation name"
val repositoryName = "your repository name"
val minVersionsToKeep = 5
val packageMaxLifetime = Period.ofDays(30)
val dryRun = true

runBlocking {
    val token = args[0]
    val organisation = args[1]
    val repositoryName = args[2]
    val minVersionsToKeep = args[3].toInt()
    val packageMaxLifetime = Period.ofDays(args[4].toInt())
    val dryRun = args.getOrNull(5)?.toBoolean() ?: false

    val gitHubRepository = GitHubRepository(token = token, organisation = organisation, repositoryName = repositoryName)

    // Step 1: fetch releases
    val releases = gitHubRepository.getReleases()
    println("Releases found in $repositoryName repository:")
    println(releases.joinToString(", ", "[", "]"))

    // Step 2: fetch all packages for the repository
    val packages = gitHubRepository.getPackages("maven")
    println("Packages found in $repositoryName repository:")
    println(packages.joinToString(", ", "[", "]") { it.name })

    packages.map { githubPackage ->
        async {
            // Step 3.1: get all versions for this github package
            val allVersions = gitHubRepository.getAllVersionsOfPackage(
                packageName = githubPackage.name,
                packageType = githubPackage.type
            )

            // Step 3: filter the versions we want to clean
            val expiredVersions = allVersions
                .sortedByDescending { it.createdAt }
                .takeLast((allVersions.size - minVersionsToKeep).coerceAtLeast(0))
                .filter {
                    it.createdAt.isBefore(
                        LocalDateTime.now().minus(packageMaxLifetime)
                    )
                }
            val versionsToDelete = expiredVersions.filter { !releases.contains(it.name) }

            println("Versions found for ${githubPackage.name}:")
            println(allVersions.joinToString(", ", "[", "]") { it.name })

            // Step 4: delete those versions
            if (dryRun) {
                println("DRY RUN: would be deleted:")
                println(versionsToDelete.joinToString(", ", "[", "]") { it.name })
            } else {
                versionsToDelete.map { version ->
                    async {
                        gitHubRepository.deletePackageVersion(
                            packageType = githubPackage.type,
                            packageName = githubPackage.name,
                            packageVersionId = version.id
                        )
                        println("${githubPackage.name} ${version.name} DELETED")
                    }
                }.awaitAll()
            }
        }
    }.awaitAll()

    exitProcess(0)
}

class GitHubRepository(
    private val token: String,
    private val organisation: String,
    private val repositoryName: String
) {

    suspend fun getReleases(): List<String> {
        val url = "https://api.github.com/repos/$organisation/$repositoryName/releases"
        val response = Fuel.loader().get(
            Request.Builder().apply {
                headers(buildHeaders())
                url(url)
            }.build()
        )

        return if (response.statusCode == 200) {
            JsonParser.parseString(response.body).asJsonArray.map {
                it.asJsonObject.get("tag_name").asString
            }
        } else {
            throw Exception("GET $url ended with exception:\nstatus code: ${response.statusCode}\n${response.body}")
        }
    }

    suspend fun getPackages(
        packageType: String
    ): List<Package> {
        val url = "https://api.github.com/orgs/$organisation/packages?package_type=$packageType"
        val response = Fuel.loader().get(
            Request.Builder().apply {
                headers(buildHeaders())
                url(url)
            }.build()
        )

        if (response.statusCode == 200) {
            val packagesResponseJson = JsonParser.parseString(response.body)
            val packages = packagesResponseJson.asJsonArray.map {
                Package(
                    id = it.asJsonObject.get("id").asString,
                    name = it.asJsonObject.get("name").asString,
                    type = it.asJsonObject.get("package_type").asString,
                    repository = it.asJsonObject.get("repository").asJsonObject.get("name").asString
                )
            }
            return packages.filter {
                it.repository == repositoryName
            }
        } else {
            throw Exception("GET $url ended with exception:\nstatus code: ${response.statusCode} \n${response.body}")
        }
    }

    suspend fun getAllVersionsOfPackage(
        packageName: String,
        packageType: String
    ): List<Version> {
        val url = "https://api.github.com/orgs/$organisation/packages/$packageType/$packageName/versions"
        val response = Fuel.loader().get(
            Request.Builder().apply {
                headers(buildHeaders())
                url(url)
            }.build()
        )

        if (response.statusCode == 200) {
            val responseJson = JsonParser.parseString(response.body)
            val versions = responseJson.asJsonArray.map {
                Version(
                    id = it.asJsonObject.get("id").asString,
                    name = it.asJsonObject.get("name").asString,
                    createdAt = LocalDateTime.parse(
                        it.asJsonObject.get("created_at").asString,
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME
                    )
                )
            }
            return versions
        } else {
            throw Exception("GET $url ended with exception:\nstatus code: ${response.statusCode} \n${response.body}")
        }
    }

    suspend fun deletePackageVersion(
        packageType: String,
        packageName: String,
        packageVersionId: String
    ) {
        val url =
            "https://api.github.com/orgs/$organisation/packages/$packageType/$packageName/versions/$packageVersionId"
        val response = Fuel.loader().delete(
            Request.Builder().apply {
                headers(buildHeaders())
                url(url)
            }.build()
        )
        if (response.statusCode != 200) {
            throw Exception("GET $url ended with exception:\nstatus code: ${response.statusCode} \n${response.body}")
        }
    }

    private fun buildHeaders(): Map<String, String> {
        return mapOf(
            "Accept" to "application/vnd.github+json",
            "Authorization" to "Bearer $token",
            "X-GitHub-Api-Version" to "2022-11-28"
        )
    }
}

class Package(
    val id: String,
    val name: String,
    val type: String,
    val repository: String
)

class Version(
    val id: String,
    val name: String,
    val createdAt: LocalDateTime
)