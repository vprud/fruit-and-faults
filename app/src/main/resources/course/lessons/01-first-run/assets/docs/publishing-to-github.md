# Publishing your learner project to GitHub

Publishing is optional. Local commits and all installed lessons work offline.
A public repository is visible to everyone: review the files before publishing,
and keep credentials and personal data out of the project.

In GitHub's website, sign in and create a new repository in your own account.
Choose a name and Public visibility. Leave README, license, and .gitignore
initialization empty so the repository starts without a competing history.
[GitHub's repository creation guide](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-new-repository)
shows the website steps.

From the learner workspace, repair and check the starter, then inspect:

```text
git status
git diff
```

Stage the files you reviewed. For the first lesson, these paths contain the
starter and the portable course state:

```text
git add .gitignore build.gradle.kts settings.gradle.kts gradlew gradlew.bat gradle docs src .fruit-and-faults/progress.json .fruit-and-faults/managed-files.json .fruit-and-faults/workspace.properties
git diff --cached
git commit -m "fix: repair starter compilation"
```

The course's progress, managed-files manifest, and workspace identity belong
in Git so a clone can resume. Build output, local caches, IDE settings, and
temporary transactions are ignored. For later lessons, stage the changed
files you reviewed and use that lesson's suggested commit message.

Copy the empty repository's HTTPS URL. Substitute your account and repository
name below. Inspect existing remotes first; add origin only if it is absent:

```text
git remote -v
git remote add origin https://github.com/YOUR-ACCOUNT/YOUR-REPOSITORY.git
git branch -m main
git push -u origin main
```

Use these commands in this fresh learner repository. If a main branch or an
origin already exists, inspect it before making changes. The push publishes
your commits and sets main's upstream; later pushes can use `git push`.

Use GitHub's normal HTTPS authentication through a credential helper, such as
Git Credential Manager, and its browser sign-in flow. Account passwords do
not authenticate Git pushes. Never paste passwords or tokens into the course
CLI, a remote URL, source code, or course state files. The CLI does not collect
or store credentials.
[GitHub's HTTPS and credential guide](https://docs.github.com/en/get-started/git-basics/about-remote-repositories)
explains the supported authentication flow.

A missing remote, upstream, network connection, or successful push never
blocks a lesson. Continue locally and publish when ready.

# Running the disclosed tests directly

Install JDK 26. On macOS and Linux run `sh ./gradlew test`; on Windows run
`gradlew.bat test`. The first build needs the wrapper distribution and JUnit
dependencies available locally; run it online once if they are not cached.
With the distribution, dependencies, and Java 26 installed, use
`sh ./gradlew test --offline` (or `gradlew.bat test --offline` on Windows).
The learner build has no online service or toolchain download requirement.
