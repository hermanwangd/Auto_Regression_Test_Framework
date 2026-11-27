# Recovery email through Mailpit

This suite sends a recovery request to an application, then reads the message accepted by Mailpit over SMTP. It checks the complete recipient list, exact subject and plaintext link. The sample link is a public fixture value. Use synthetic mail only because HTTP response evidence includes message contents.

Supply a fresh, dedicated Mailpit inbox for each run. The application must send to that inbox and acknowledge the request after SMTP acceptance. The suite checks that the inbox was initially empty and received exactly one message. It leaves the inbox intact for inspection.

Set REST_BASE_URL to the application origin, MAILPIT_BASE_URL to Mailpit's HTTP origin, and REGRESS_REST_ALLOWED_ORIGINS to both origins separated by a comma. Run the packaged CLI using the manifest in this directory.

    java -jar "$FRAMEWORK_JAR" run --suite suite_manifest.yaml --profile external_mailpit

From a source checkout, scripts/ci/verify-mailpit-flow.sh starts a local application and pinned Mailpit, then exercises successful delivery and independent recipient, subject and link failures.
