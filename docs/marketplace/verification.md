# Marketplace material checks

Run from the repository root in PowerShell on Windows after building the exact candidate ZIP:

```powershell
.\scripts\verify-marketplace.ps1 -Version '1.0.0-rc.2'
# After the release materials and recorded decisions have been prepared:
.\scripts\verify-marketplace.ps1 -Version '1.0.0-rc.2' -Ready
```

The default **materials-only** mode checks the local package, logo, copy and screenshot set. It reports unresolved license, privacy, publisher/contact, distribution-material scope or older capture evidence as **PENDING**; those decisions are not required for a materials-only pass. `-Ready` fails on any PENDING item. An invalid required asset fails either mode. JSON reports are written to `build/reports/marketplace-checks-<version>[-ready].json`; failed runs return a nonzero exit code. Material readiness does **not** require or grant authorization to upload: `uploadAuthorized: false` is consistent with a successful local ready gate.

This is a local preflight, **not** Marketplace approval, proof of account ownership/readiness, GUI acceptance, legal advice or permission to upload. Contact syntax is checked; availability and ownership are not. Use `scripts/verify-release.ps1` separately for tests, API verification and closed-sandbox package/log checks. Review the rendered logos, listing and screenshots manually before submission.

## Checks and bounded SVG support

- The distribution must contain its one expected versioned plugin JAR, a matching plugin ID/version, and `META-INF/pluginIcon.svg` / `pluginIcon_dark.svg` identical to the source assets.
- The packaged `META-INF/LICENSE.txt` must match `docs/marketplace/EULA.md` byte-for-byte. Its SHA-256 is included in the report. Once publishing decisions are recorded, the chosen license path must be this EULA, and the packaged vendor name/URL must match `publisher.name` / `publisher.vendorProfile`. Editing these materials after a build requires rebuilding the package; a visual copy of similar text is insufficient.
- Both logos must be 40 × 40 SVGs, `viewBox="0 0 40 40"`, each below 3 KiB. Geometry plus stroke must remain within `[2, 38]` in both axes. No external resources, scripts, CSS, transforms, filters, masks, nested viewports or embedded raster images are accepted.
- The static geometry check supports paths (`M/L/H/V/C/S/Q/T/Z`, including relative forms), rectangles, circles, ellipses, lines and polygons. Bezier control-point hulls are checked conservatively; they can reject a visually safe curve. Arc commands are not supported. Stroked paths/polylines/polygons use explicit `round` or `bevel` joins. These restrictions describe the project's logo contract, not all SVGs allowed by JetBrains. This check does not certify visual contrast, legibility or branding.
- `listing.html`, `getting-started.html`, `release-notes.html` and `FAQ.md` must exist under `docs/marketplace`, contain substantive copy, and have no recognized `TODO`, `TBD`, `PLACEHOLDER` or `{{...}}` tokens. This does not establish editorial accuracy.
- Public copy must not link to this project's private GitHub source repository. For the recorded `free-closed-source` choice, the descriptor is also checked for that repository URL; decisions must retain free pricing, private source visibility and `sourceCodeRightsGranted: false`. These consistency checks do not inspect GitHub permissions or determine legal rights.
- Every screenshot must exist, match its checksum and dimensions, be at least 1200 × 760, and share the set's exact aspect ratio. Its original local screenshot, checksum, capture version/JAR hash and evidence document must be recorded. Upscaling and undeclared changes fail. Transform labels document the editorial process; hashes alone cannot prove authentic pixels or validate redactions.

## Screenshot manifest

`docs/marketplace/media/manifest.json` has `schemaVersion: 1`, the release `version`, the actual distribution's `packageSha256`, and a non-empty `screenshots` array. Each entry uses:

| Field | Meaning |
|---|---|
| `file`, `width`, `height`, `sha256` | Uploaded image, relative to the manifest directory; actual dimensions and lowercase SHA-256. |
| `source.file`, `source.sha256` | Original captured image relative to the repository root, and its lowercase SHA-256. Local evidence must remain available when checking. |
| `source.pluginVersion`, `source.packagedJarSha256` | Version and exact JAR that were loaded for the capture; never fill these from a different package to make a check pass. |
| `source.evidence` | Existing repository-relative capture/GUI evidence document. |
| `source.transforms` | Array containing only `crop`, `downscale` and/or `privacy-redaction`; empty for an unchanged image. Do not fabricate target data or UI. |

The root package hash must match the candidate ZIP. Older-version or different-JAR source evidence is reported as PENDING in materials mode and fails `-Ready`. Preserve the original capture provenance even when reusing an older screenshot. No absolute paths or parent-directory escapes are accepted.

## Public-distribution decisions

`docs/marketplace/publishing-decisions.json` records four objects:

- `license`: `status` is `pending` or `decided`; a decided item also names `kind` and an existing repository-relative `file` containing the chosen license/EULA. An open-source license is not assumed or required by this checker.
- `publisher`: `status` is `pending` or `decided`; a decided item has the actual `name`, `vendorProfile` URL matching the packaged descriptor, and an HTTPS support URL or email in `contact`.
- `privacy`: `status` is `pending` or `decided`; a decided item has `file` pointing to the repository-relative privacy disclosure, currently `docs/marketplace/privacy-policy.html`.
- `distribution`: `status` is `pending`, `prepared`, `decided` or `approved`. `prepared` and `decided` are enough for this material-preparation gate. Preserve the owner's actual intent in fields such as `uploadAuthorized: false` and `sourceRepositoryVisibility: private`; running this script never grants upload approval.

A missing decisions file is PENDING, not permission to publish. `-Ready` checks that these local declarations and required files are present; it cannot establish their legal validity, whether the account is configured, or whether a live Marketplace submission would be accepted. Publishing remains a separate, explicitly authorized action.

## Owned demo target for captures

```powershell
.\scripts\run-marketplace-demo.ps1 -JdkHome '<complete-JDK-21-directory>' -DurationSeconds 600
```

This separate fixture compiles into `build/fixture-classes`, starts a new JVM with an authenticated loopback JMX endpoint, and prompts for a disposable password with `Read-Host -AsSecureString`. Use the printed JMX URL with `operator` (read/write) or `observer` (server-enforced read-only) and that password. For an automation, `BEACON_DEMO_PASSWORD` can supply the same one-shot input; the script removes it from the current process environment before creating children. The password travels over UTF-8 stdin, not process arguments or logs. Mutable script-owned copies are cleared; immutable environment, runtime and target-JVM copies cannot be guaranteed erased. Never use a real account password.

The duration is bounded to 1–600 seconds. The script waits for normal exit and its `finally` terminates only its own child if still running. `--public-demo` aliases the reported Runtime.Name host to `beacon-demo` to avoid publishing the workstation name; sampled metrics, MBeans and operations remain real. It does not connect to existing processes and does not open an unprotected remote management port. Record the actual resulting connection and loaded plugin JAR in screenshot evidence; this fixture does not generate or certify screenshots.
