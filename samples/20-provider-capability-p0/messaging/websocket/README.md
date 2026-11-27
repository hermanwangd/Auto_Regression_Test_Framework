# Native WebSocket order event

This standalone v0.3 suite subscribes to an order stream, performs `POST /orders/change` through the REST provider and waits for an `order.changed` event with the same correlation ID. The WebSocket setup waits for a server acknowledgement before allowing the HTTP action. The cleanup step closes the connection after either outcome.

Set `FRAMEWORK_WEBSOCKET_URI` to the event endpoint and `REST_BASE_URL` to the application origin. The endpoint must be an absolute `ws://` or `wss://` URI without user information, query or fragment. TLS uses the runner's default trust configuration. This profile uses an already-authorized environment with no handshake credentials.

```sh
./regress run --suite samples/20-provider-capability-p0/messaging/websocket/suite_manifest.yaml --profile external_websocket
./regress validate-evidence --result <result.json>
```

The example server accepts the JSON object in `fixtures/subscribe.json`, returns `{"event":"subscribed"}` and copies the POST's `correlation_id` into the event's `/meta/correlation_id`. For a stream that starts delivering immediately after the handshake, use `with: {}` for subscription. Otherwise supply the subscription fixture, acknowledgement pointer and expected string together. The provider accepts UTF-8 JSON text objects and compares string fields at JSON Pointers.

Each message and subscription fixture is limited to 8 KiB. A connection accepts at most 64 complete messages including acknowledgements, and each message can contain at most 128 fragments. The verification step accepts 1–64 events and a 1–10,000 ms timeout. Setup and close handshakes have bounded waits. Evidence records the generated correlation ID, match status, inspected event count and cleanup result. Frame contents, endpoint and peer close reason stay out of provider evidence.

The repository's `scripts/ci/verify-native-websocket.sh` starts a disposable loopback application using Python's standard library and runs this sample through the packaged CLI. The application sends an unrelated correlation ID and a different event for the current correlation before it emits the expected event. The script checks the successful observation, then repeats with a changed expectation and checks the failure and both close handshakes.
