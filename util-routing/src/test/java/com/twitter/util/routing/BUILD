junit_tests(
    sources = [
        "*.java",
    ],
    compiler_option_sets = ["fatal_warnings"],
    strict_deps = True,
    tags = [
        "bazel-compatible",
        "non-exclusive",
    ],
    dependencies = [
        "3rdparty/jvm/junit",
        "3rdparty/jvm/org/scala-lang:scala-library",
        "3rdparty/jvm/org/scalatestplus:junit",
        "util/util-core:util-core-util",
        "util/util-logging/src/main/scala",
        "util/util-routing/src/main/scala/com/twitter/util/routing",
    ],
)
