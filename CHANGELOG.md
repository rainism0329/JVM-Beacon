# Changelog

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
