### Deleting GitHub Packages with Kotlin Script

This repository contains a full code of a script in Kotlin, described in this Medium article. It makes API calls to GitHub API to fetch and delete certain GitHub Package versions.

This script has 6 input parameters:
- token
- organisation name
- repository name
- number of the latest versions to keep
- number of the days package should be not older than
- "dry run" (if true, the script will only type in console the package, which could be deleted, without making a actual deletion call)

Example:
```
kotlinc -script cleaner.main.kts "ghp_xxx" "organisation" "repository" 5 30 true
```

⚠️ Script also prevent all release versions of packages from being deleted. 