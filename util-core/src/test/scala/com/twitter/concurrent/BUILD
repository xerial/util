junit_tests(
    sources = ["*.scala"],
    compiler_option_sets = ["fatal_warnings"],
    strict_deps = True,
    tags = [
        "bazel-compatible",
        "non-exclusive",
    ],
    dependencies = [
        "3rdparty/jvm/junit",
        "3rdparty/jvm/org/mockito:mockito-core",
        "3rdparty/jvm/org/scalacheck",
        "3rdparty/jvm/org/scalatest",
        "3rdparty/jvm/org/scalatestplus:junit",
        "3rdparty/jvm/org/scalatestplus:mockito-3-3",
        "3rdparty/jvm/org/scalatestplus:scalacheck",
        "util/util-core:util-core-util",
        "util/util-core/src/main/scala/com/twitter/concurrent",
        "util/util-core/src/main/scala/com/twitter/conversions",
        "util/util-core/src/test/java/com/twitter/util:object_size_calculator",
    ],
)
