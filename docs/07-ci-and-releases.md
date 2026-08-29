# CI, Releases & Build Verification

> Part of the codebase notes series. Index: [docs/README.md](README.md)

Goal: nobody should have to trust a jar that a maintainer uploaded by hand. Every binary is built **from the sources in this repository, on GitHub-hosted runners**, with checksums and cryptographically signed provenance that anyone can verify.

## Workflows

### `.github/workflows/build.yml` — CI build
Triggers: every push, every pull request, manual dispatch.

1. `actions/checkout` — clean checkout of the pushed commit.
2. `gradle/actions/wrapper-validation` — verifies the committed `gradle-wrapper.jar` against Gradle's official checksum list (defends against a tampered wrapper binary — the one binary blob a Gradle repo has to carry).
3. `actions/setup-java` — Temurin **JDK 8** (what the codebase targets) + Gradle dependency cache.
4. `./gradlew jar jar_source jar_server jar_client serverDist` with `-Poverride_version=indev-<short-sha>` (skips git-tag versioning; see [02-build-system.md](02-build-system.md)).
5. SHA-256 checksums of all outputs are printed to the job summary and uploaded together with the artifacts (retention-limited CI artifacts, downloadable from the run page).
6. **Boot smoke test** — the freshly built `server-dist` zip is unpacked and started on the runner; the run fails unless the server reaches `Done` (fully started, all three default worlds initialized) within 8 minutes, after which it is stopped via the console. A green build means "compiles *and boots*", not just "compiles".

### `.github/workflows/release.yml` — releases
Trigger: pushing a tag matching `v*` (e.g. `v0.1.6`). Version = tag without the `v`.

Builds the same task set and **runs the same boot smoke test** (nothing is published if the server does not start), then:

1. Collects `*-dev.jar`, `*-sources.jar`, `*-server.jar`, `*-client.jar`, `*-server-dist.zip` and writes `SHA256SUMS.txt`.
2. **`actions/attest-build-provenance`** — generates a signed [artifact attestation](https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations) for every jar/zip: a Sigstore-signed statement, stored by GitHub, binding the artifact's SHA-256 digest to this repository, the exact commit, the workflow file and the run that produced it.
3. Publishes a GitHub Release with all files + `SHA256SUMS.txt` + auto-generated changelog + verification instructions in the body.

Release artifacts:

| File | What it is |
|---|---|
| `..._-server-dist.zip` | **Runnable server**: server jar + `libraries/` (the jar's manifest `Class-Path`) + `start.sh`/`start.cmd`. Unzip and run. |
| `..._-server.jar` | Server core only (obfuscated/notch names); needs a `libraries/` folder next to it |
| `..._-client.jar` | Client-side build of the core |
| `..._-dev.jar` | Unsplit build in MCP names — for compiling mods/extensions against |
| `..._-sources.jar` | Source jar |
| `SHA256SUMS.txt` | SHA-256 of every file above |

## How users verify a download

**Integrity** (the file is exactly what CI produced — catches corrupted/replaced downloads):

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing
```

**Provenance** (the file was built by *this repo's* release workflow from a public commit — catches maliciously rebuilt/backdoored jars even if someone re-uploaded assets):

```bash
gh attestation verify ultramine_core-1.7.10-<ver>-server.jar \
    --repo SashaGoncharov19/UltraMine-updated
```

The output names the commit SHA, workflow path and run. Anyone can then read the exact source that went into the binary. Verification works offline against GitHub's Sigstore bundle and needs only the [gh CLI](https://cli.github.com/) — no keys to distribute.

**Reproducing locally**: `cd ultramine/ultrasource && ./gradlew jar_server serverDist` on JDK 8 produces the same artifacts (bit-for-bit reproducibility is *not* guaranteed — jar timestamps differ — but the class content can be diffed with any jar-diff tool).

## Trust model, honestly stated

- What this protects against: hand-uploaded binaries with malware, tampered release assets, a compromised maintainer laptop, a swapped `gradle-wrapper.jar`.
- What it does not protect against: malicious code *in the sources themselves* (review commits — the repo is the source of truth), compromise of GitHub Actions infrastructure, or malicious dependencies (deps are pulled from Maven Central / `libraries.minecraft.net` / `maven.minecraftforge.net` by pinned version; a follow-up hardening step is Gradle dependency verification with checksums — see [06-modernization-notes.md](06-modernization-notes.md)).
- The legacy `ultramine/libraries/*.jar`, `libraries.zip` and `bootstrap.jar` blobs committed in this repo predate the CI pipeline and are **not** covered by attestations; prefer release artifacts.

## Cutting a release (maintainer workflow)

```bash
git tag v0.1.6
git push origin v0.1.6
# release.yml builds, attests and publishes the GitHub Release automatically
```

Versioning stays manual-by-tag (`MAJOR.MINOR.PATCH` after a `v`). The Gradle-side git-describe versioning still exists for local builds but CI always passes an explicit `-Poverride_version`.
