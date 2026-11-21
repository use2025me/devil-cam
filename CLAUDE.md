# Devil Cam: build rules

Keep every build light (the owner's laptop overheats on heavy builds). Don't undo these:

- `gradle.properties`: `org.gradle.workers.max=1`, `org.gradle.priority=low`,
  `org.gradle.caching=true`, `kotlin.compiler.execution.strategy=in-process`.
- Skip the lint release check: `lint { checkReleaseBuilds = false }` in `app/build.gradle.kts`,
  so no lintVital tasks run.
- Run unit tests on the release variant in the same Gradle run as the APK
  (`testReleaseUnitTest assembleRelease`). Never add a separate debug build just for tests.
