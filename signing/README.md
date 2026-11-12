# Signing

**Releases** are signed with GAKO's private release key. It isn't in this repo: CI reads it from
the `GAKO_KEYSTORE_B64` and `GAKO_KEYSTORE_PASSWORD` repository secrets, and a tagged release
fails rather than fall back to anything else. Its certificate's SHA-256 is printed at the bottom
of every release's notes, so anyone can check an APK came from here.

`gako-dev.jks` is the **development** key. It's committed and public on purpose, so local builds
and pull requests from forks (which can't see secrets) still produce an installable APK. Never use
it for anything people install from: anyone can sign with it.
