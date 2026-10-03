# CLI transcripts

UTF-8/LF fixtures pin stdout/stderr separately for noninteractive checking and
the line-oriented numbered reflection view. Interactive prompts are exercised
by `CommandJourneyTest` with bounded injected input; absent console/TTY never
reads stdin. Color is absent here and is tested separately with explicit
interactive capability.
