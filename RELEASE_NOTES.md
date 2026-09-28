# ETS Enerji Takip 2.0.3 — stability update

- Admin login no longer rejects authenticated admins solely because their company list is empty or parsing fails.
- Account change and logout await native cookie clearing before proceeding.
- Native HTTP requests run on a dedicated serial executor, with asynchronous JavaScript callbacks and timeouts.
- Company refresh replaces old records with a valid empty list; redirects to login are detected.
- Saved report data is not reused, and failed scans do not announce a successful synchronization.
- Main WebView cannot navigate away from the local app while its privileged JavaScript bridge is attached.
- Version number and CI APK artifact label updated.

## Outstanding external dependencies

HTTPS cannot be enabled safely solely by editing the app while the ETS server is HTTP-only. Configure a valid TLS endpoint and then migrate the base URL / cleartext policy. Reliable terminated-app notifications require server push or an authenticated background API; the current timer is foreground-only. The project requires a real APK compilation and device tests with both account types. Do not ship until these are verified.

## GitHub build fix
- Merged duplicate onBackPressed() overrides into one handler. Portal navigation takes priority, then main app WebView history, then system Back.
- Source-level verification only; rerun GitHub Actions to verify APK compilation.
