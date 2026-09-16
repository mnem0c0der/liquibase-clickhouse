# Releasing

Publishing to Maven Central is performed by the repository owner. It is
irreversible: a released version can never be modified or removed.

## One-time setup

1. Claim the `io.github.mnem0c0der` namespace at https://central.sonatype.com
   by adding the verification TXT record or repository it asks for.
2. Generate a publishing token (Account -> Generate User Token).
3. Create a GPG key and publish the public half:

   ```bash
   gpg --gen-key
   gpg --list-secret-keys --keyid-format=long
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>
   ```

4. Add four repository secrets in GitHub (Settings -> Secrets and variables ->
   Actions):

   | Secret | Value |
   |---|---|
   | `CENTRAL_TOKEN_USERNAME` | token username from step 2 |
   | `CENTRAL_TOKEN_PASSWORD` | token password from step 2 |
   | `GPG_PRIVATE_KEY` | full armored private key from step 3 |
   | `GPG_PASSPHRASE` | passphrase for that key |

You only do this once. Skip straight to "Cutting a release" next time.

## Cutting a release

1. Make sure `main` is green in CI.
2. Set the release version and update `CHANGELOG.md` (move `[Unreleased]`
   entries under a new `## [1.0.0] - YYYY-MM-DD` heading):

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 21)
   mvn -B versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
   git commit -am "chore: release 1.0.0"
   ```

3. Tag and push. The tag must match the project version exactly, with a `v`
   prefix — the release workflow refuses to run otherwise.

   ```bash
   git tag v1.0.0
   git push origin main v1.0.0
   ```

4. Watch the "Release" workflow in the Actions tab. It builds, signs and
   uploads the artifact as a draft deployment in the Central Portal.
5. Review the deployment at https://central.sonatype.com and press Publish.
   **Nothing reaches Maven Central until this manual step.** Check the
   contents of the uploaded jars first; there is no undo after publishing.
6. Bump to the next snapshot:

   ```bash
   mvn -B versions:set -DnewVersion=1.1.0-SNAPSHOT -DgenerateBackupPoms=false
   git commit -am "chore: back to snapshot"
   git push
   ```

## Verifying a release locally before tagging

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B clean verify -Prelease -pl liquibase-clickhouse -am
```

This signs the artifacts without uploading anything. Check
`liquibase-clickhouse/target/` for the four files Central requires: the main
jar, `-sources.jar`, `-javadoc.jar`, and an `.asc` signature next to each.

If it fails at the `sign-artifacts` step, either no GPG key is configured
locally (see step 3 above) or the key needs `--pinentry-mode loopback` to read
the passphrase non-interactively; the profile already passes that flag.
