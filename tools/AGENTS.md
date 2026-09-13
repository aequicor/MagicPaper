# Development tools

`mission-visualization` is an independent Git submodule with its own build and
instructions. Keep its generic extension API free of MagicPaper dependencies.
Commit its changes separately before updating the parent gitlink.

`paper-plugin` owns the adapter and isolated Paper fixtures. `paper-editor` is the
desktop composition root. Both consume public Paper API; neither depends on app
or feature implementations. Enable them with `-PpaperEditor=true`.

The ordinary chat invokes the desktop editor through its local batch/launch protocol.
`feature/chat/api` owns `LayoutEditor`; chat implementation owns orchestration and
the JVM process/file adapter. Keep tool code independent of chat implementation.
Do not restore a chat by launching the editor, or redirect a bound conversation to
the newly selected sidebar project. Validate before atomic publication, preserve
manual edits, and keep temporary render files outside the watched layout directory.
