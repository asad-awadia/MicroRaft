# Contributing to MicroRaft

By contributing code to the MicroRaft project in any form, including sending 
a pull request via Github, a code fragment or patch by any public means, you
agree to release your code under the terms of the Apache 2.0 license that you
can find in the MicroRaft repository. 

## Local Setup

MicroRaft builds with Gradle. The default toolchain target is Java 11 and can
be overridden locally with the `microraft.javaVersion` Gradle property when you
want to run the build on a newer installed JDK.

Useful commands:

- `./gradlew build` builds all modules.
- `./gradlew check` runs tests, Checkstyle, and SpotBugs.
- `./gradlew qualityDashboard` generates a combined quality report at `build/reports/quality/index.html`.
- `./gradlew :microraft-tutorial:test --tests io.microraft.tutorial.OperationCommitTest -Pmicroraft.javaVersion=21` runs the quickest local tutorial flow on a newer JDK.

If Java 11 is installed on your machine, you can omit `-Pmicroraft.javaVersion=21`.
If you only have a newer local JDK, set `-Pmicroraft.javaVersion=<your-installed-version>`
to align Gradle's toolchain request with that JDK.

## Repository Map

- `microraft`: core Raft implementation and the main test suite.
- `microraft-tutorial`: runnable local tutorial scenarios and sample state machines.
- `microraft-metrics`: Micrometer integration.
- `microraft-hocon`, `microraft-yaml`: configuration helper modules.
- `microraft-store-sqlite`: SQLite-backed storage implementation.
- `site-src`: source files for `microraft.io`.

## How to Report an Issue

If you want to report an issue or a bug, please provide details, such as Java
version, JVM parameters, logs or stack traces, operation system, and steps to 
reproduce your issue. I would be grateful If you could include a unit or an 
integration test as a reproducer.


## How to Ask a Question or Discuss a Feature Request

You can chime in to [Discussions](https://github.com/MicroRaft/MicroRaft/discussions) 
for your questions, ideas or feature requests.

## How to Provide a Code Change

No direct commits are allowed to the MicroRaft repository. If you want to 
provide a code change:

1. Fork MicroRaft on Github,
2. Create a branch for your code change, 
3. Push to your branch on your fork,
4. Create a pull request to the MicroRaft repository.

MicroRaft contains `checkstyle` and `spotbugs` tools for static code analysis.
Please run `./gradlew checkstyleMain` and 
`./gradlew spotbugsMain` locally before issuing your pull request.

Thanks for your help and effort!
