# Versioning and releases

## Scheme

[Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html): `MAJOR.MINOR.PATCH`.

| Change kind                       | Commit type                   | Version bump |
| --------------------------------- | ----------------------------- | ------------ |
| Breaking API/DB change            | `feat!:` / `BREAKING CHANGE:` | MAJOR        |
| New functionality                 | `feat:`                       | MINOR        |
| Defect fix, performance, refactor | `fix:`, `perf:`, `refactor:`  | PATCH        |
| Docs, chore, test, build, ci      | `docs:`, `chore:`, …          | none         |

The current version is stored in two places that **must stay identical**:

- the `VERSION` file at the repository root (single line, newline at end);
- `<version>` of the root `pom.xml` (`claims-platform`).

The `repo-hygiene` job of `ci.yml` fails the build when they diverge.

## How a release happens

1. A pull request is squash-merged into `main` with a Conventional Commit title (validated by
   `pr-title.yml`).
2. `release.yml` runs `semantic-release` on `main`: it analyses the commits since the last tag,
   writes `CHANGELOG.md`, creates the tag `vX.Y.Z` and publishes the GitHub Release.
3. The tag triggers `container-images.yml`, which builds and pushes the semver tags
   (`X.Y.Z`, `X.Y`) in addition to `sha-<short>` and `latest`.

Note (ADR-0004): `semantic-release` does **not** bump `VERSION` or the `pom.xml`. Aligning them is a
deliberate manual/agent step, done together with the code change that introduces the version, so that
the Maven build never depends on a tag existing.

## Manual bump

```bash
# 1. Update VERSION and the root POM in one go
echo "0.2.0" > VERSION
./mvnw -N versions:set -DnewVersion=0.2.0
./mvnw -N versions:commit

# 2. Keep module parents aligned (they inherit from claims-platform)
./mvnw -B -ntp verify

# 3. Commit
git commit -am "chore(release): prepare 0.2.0"
```

`docs/api/*.openapi.yaml` carry `info.version`; update it in the same commit when the contract changes.

## Container image tags

`docker/metadata-action` produces, for `ghcr.io/france012/test_newera/<service>`:

| Tag           | When           |
| ------------- | -------------- |
| `X.Y.Z`       | on a `v*` tag  |
| `X.Y`         | on a `v*` tag  |
| `sha-<short>` | every build    |
| `latest`      | on `main` only |

The registry path is lowercased by a dedicated workflow step because GHCR rejects uppercase
(ADR-0011). Kubernetes manifests reference images **by digest**, never by tag.

## Support policy

`0.1.x` receives fixes; older minors are not maintained. A MAJOR bump requires a migration note in
`CHANGELOG.md` and in the PR template's "Breaking change" field.
