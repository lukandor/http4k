description = "HTTP Client built on top of okhttp"

dependencies {
    api(project(":http4k-core"))
    api(Square.okHttp3)
    testImplementation(project(path = ":http4k-core", configuration ="testArtifacts"))
}
