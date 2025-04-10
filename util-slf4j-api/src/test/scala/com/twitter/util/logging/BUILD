scala_library(
    name = "testutil",
    sources = [
        "!*Test.scala",
        "*.java",
        "*.scala",
    ],
    strict_deps = True,
    tags = ["bazel-compatible"],
    dependencies = [
        "3rdparty/jvm/org/mockito:mockito-core",
        "3rdparty/jvm/org/scalatest",
        "3rdparty/jvm/org/scalatestplus:junit",
        "3rdparty/jvm/org/scalatestplus:mockito-3-3",
        "3rdparty/jvm/org/scalatestplus:scalacheck",
        "3rdparty/jvm/org/slf4j:slf4j-api",
        "util/util-core/src/main/scala/com/twitter/io",
        "util/util-slf4j-api/src/main/scala/com/twitter/util/logging",
        "util/util-slf4j-api/src/test/resources",
    ],
    exports = [
        "3rdparty/jvm/org/slf4j:slf4j-api",
        "util/util-core/src/main/scala/com/twitter/io",
        "util/util-slf4j-api/src/main/scala/com/twitter/util/logging",
        "util/util-slf4j-api/src/test/resources",
    ],
)

junit_tests(
    compiler_option_sets = ["fatal_warnings"],
    strict_deps = True,
    tags = [
        "bazel-compatible",
        "non-exclusive",
    ],
    dependencies = [
        ":testutil",
        "3rdparty/jvm/org/mockito:mockito-scala",
        "3rdparty/jvm/org/scalatest",
        "3rdparty/jvm/org/scalatestplus:junit",
        "3rdparty/src/jvm/org/scalatestplus/mockito",
        scoped(
            "3rdparty/jvm/org/slf4j:slf4j-simple",
            scope = "runtime",
        ),
        "util/util-mock/src/main/scala/com/twitter/util/mock",
    ],
)
