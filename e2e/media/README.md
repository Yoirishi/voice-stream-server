# Media E2E Harness

This harness runs browser-level WebRTC checks with fake camera and microphone devices.

Current coverage:

- starts two Chromium browser clients;
- captures fake audio and video tracks with `getUserMedia`;
- connects the clients with `RTCPeerConnection`;
- exchanges SDP and ICE candidates inside the test;
- checks `getStats()` until inbound RTP packets or bytes are observed.

Run locally after installing Node dependencies:

```shell
cd e2e/media
npm install
npm test
```

Run through Docker Compose:

```shell
docker compose -f compose.e2e.yaml up --abort-on-container-exit --exit-code-from media-e2e media-e2e
```

The current test validates browser media plumbing without a selected SFU. Once the SFU is chosen, keep the same fake-media setup and replace the direct peer connection exchange with the SFU SDK plus backend-issued media tickets.

The Docker Compose file also contains backend and PostgreSQL services for the later full-stack media path, but the current `media-e2e` service does not depend on them yet.
