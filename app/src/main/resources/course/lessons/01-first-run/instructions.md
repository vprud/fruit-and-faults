# First run and diagnostics

Goal: repair one intentional compilation error in the learner starter.

Run `fruit-and-faults check` from your learner workspace. Read the category,
source location, and compiler message. Make the smallest repair suggested by
the diagnostic, then run the check again.

Compilation must succeed before tests can execute. A failing assertion means
the code compiled, but the observed result differed from the expected result.
Before: a compilation diagnostic prevents tests from running.
After: compilation and visible starter tests succeed, and the public result
matches the lesson contract.

Review `git status` and `git diff`, then create your first local commit using
`fix: repair starter compilation`. Read the supplied GitHub publishing guide
when the starter is disclosed. Publishing is optional and requires network
access; lesson completion works locally.
