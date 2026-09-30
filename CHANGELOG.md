# Changelog

## 1.0.0-rc.2 — 2026-09-30

- Add original light/dark Marketplace and Plugin Manager SVG logos.
- Identify the publisher as Philip Zhang, with the existing PhilZ Dev vendor page.
- Prepare English Marketplace copy, onboarding, FAQ, privacy disclosure and a free proprietary EULA; bundle the EULA in the plugin.
- Capture real product screenshots from this package and controlled JVMs, with file hashes and evidence provenance.
- Add local material validation, packaging and a bounded authenticated publication fixture. Only the fixture's reported hostname is aliased; metrics and MBean results remain real.

Preparation only: no Marketplace upload, GitHub Release or public source repository.

## 1.0.0-rc.1 — 2026-09-28

First release candidate for private evaluation. No Marketplace or GitHub Release has been published.

- Complete the existing local/remote JMX, MBean, telemetry, platform-thread, snapshot and bounded JFR workflows before expanding scope.
- Clear plugin-owned password arrays when credential work is refused, cancelled before starting, fails or completes. Preserve input ownership while an underlying call is still running; discard late PasswordSafe results.
- Keep local JFR filter selectors and search editable during live polling, preventing short background requests from closing popups or stealing focus. Applying analysis still obeys request admission.
- Add installation, rollback, first-use, troubleshooting and data-handling guidance in English.
- Add an isolated acceptance-sandbox option and reproducible package verification.

The supported and actually tested environment, package fingerprint, GUI results and limitations are recorded in [release acceptance](docs/release-candidate.md). This candidate does not certify all IDE builds/JDKs/operating systems allowed by API compatibility.

## 0.15.0

- Separate MBean metadata from explicit per-attribute reads; browsing/filtering no longer invokes all getters.
- Read back only a successfully written readable attribute; expose write-only/unsupported behavior.
- Use resizable master/detail layouts, operation search and bounded per-object captured values.

Earlier implementation and validation history: [validation records](docs/validation.md).
