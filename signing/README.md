# Signing

`gako-dev.jks` is GAKO's **development** signing key. It's committed so every CI and local
build is signed the same way and installs over the previous one without uninstalling.

It is not a secret. Before publishing GAKO anywhere people install from (Play Store,
F-Droid, public releases), make a new key, keep it out of the repo, and give it to CI as the
`GAKO_KEYSTORE_B64` and `GAKO_KEYSTORE_PASSWORD` repository secrets. Builds then use that
key instead. (Android treats a key change as a different app, so expect one reinstall.)
