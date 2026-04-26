# Media E2E Harness

This harness runs browser-level WebRTC checks with fake camera and microphone devices.

Current coverage:

- starts two Chromium browser clients;
- registers two backend users through REST auth;
- inserts a temporary `VOICE` channel fixture into PostgreSQL;
- starts and joins a backend media session through GraphQL;
- connects both browser clients to LiveKit with backend-issued `participantToken` tickets;
- publishes fake microphone and camera tracks from one client;
- waits until the other client subscribes to remote audio and video tracks.

Run locally after installing Node dependencies and with backend + PostgreSQL + LiveKit available:

```shell
cd e2e/media
npm install
npm test
```

Run through Docker Compose:

```shell
docker compose -f compose.e2e.yaml up --abort-on-container-exit --exit-code-from media-e2e media-e2e
```

`compose.e2e.yaml` now runs the full-stack path: PostgreSQL, backend in Quarkus dev mode, LiveKit, and the Playwright runner.
