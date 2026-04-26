import { expect, Page, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { createServer, Server } from 'node:http';
import { createRequire } from 'node:module';
import { AddressInfo } from 'node:net';
import { Client as PgClient } from 'pg';

const require = createRequire(import.meta.url);
const liveKitBundlePath = require.resolve('livekit-client');

const backendBaseUrl = process.env.BACKEND_BASE_URL ?? 'http://127.0.0.1:8080';
const postgresUrl = process.env.POSTGRES_URL ?? 'postgres://voice_stream:voice_stream@127.0.0.1:5432/voice_stream';
const mediaOriginHost = '127.0.0.1';
const mediaOriginPort = 4173;

type RegisteredUser = {
  userId: string;
  accessToken: string;
};

type MediaJoinTicket = {
  mediaSessionId: string;
  roomName: string;
  serverUrl: string;
  participantToken: string;
};

test('fake audio and video tracks flow between two browser clients through LiveKit', async ({ browser }) => {
  await waitForHttpReady(`${backendBaseUrl}/q/graphql-ui/`);
  await waitForHttpReady('http://livekit:7880/');

  const publisherUser = await registerUser('media_e2e_publisher');
  const subscriberUser = await registerUser('media_e2e_subscriber');
  const channelId = await createVoiceChannel(publisherUser.userId);
  const publisherTicket = await startMediaSession(publisherUser.accessToken, channelId);
  const subscriberTicket = await joinMediaSession(subscriberUser.accessToken, publisherTicket.mediaSessionId);

  expect(subscriberTicket.roomName).toBe(publisherTicket.roomName);
  expect(subscriberTicket.serverUrl).toBe(publisherTicket.serverUrl);

  const origin = await startLocalMediaOrigin();
  const context = await browser.newContext();
  await context.addInitScript({ path: liveKitBundlePath });
  await context.grantPermissions(['camera', 'microphone'], { origin: origin.baseUrl });

  const publisher = await context.newPage();
  const subscriber = await context.newPage();

  try {
    await installLiveKitHarness(publisher, origin.baseUrl);
    await installLiveKitHarness(subscriber, origin.baseUrl);

    await subscriber.evaluate(async (ticket) => {
      await window.liveKitHarness.connect(ticket);
    }, subscriberTicket);
    await publisher.evaluate(async (ticket) => {
      await window.liveKitHarness.connect(ticket);
      await window.liveKitHarness.publishFakeTracks();
    }, publisherTicket);

    await expectInboundMedia(subscriber);
  } finally {
    await Promise.all([
      publisher.evaluate(async () => window.liveKitHarness?.disconnect()).catch(() => undefined),
      subscriber.evaluate(async () => window.liveKitHarness?.disconnect()).catch(() => undefined),
    ]);
    await context.close();
    await origin.close();
  }
});

async function installLiveKitHarness(page: Page, baseUrl: string): Promise<void> {
  await page.goto(baseUrl);
  await page.evaluate(() => {
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error(
        `getUserMedia is unavailable at ${window.location.href}; secureContext=${window.isSecureContext}`,
      );
    }

    const { Room, RoomEvent } = window.LivekitClient;
    const remoteTrackKinds = new Set<string>();
    const remoteTracksByKind = new Map<string, Array<{ mediaStreamTrack?: MediaStreamTrack }>>();
    let room: {
      connect(serverUrl: string, token: string, options?: unknown): Promise<void>;
      disconnect(): Promise<void> | void;
      localParticipant: {
        setMicrophoneEnabled(enabled: boolean): Promise<unknown>;
        setCameraEnabled(enabled: boolean): Promise<unknown>;
      };
      on(event: string, listener: (...args: any[]) => void): void;
    } | null = null;

    const rememberRemoteTrack = (kind: string, track: { mediaStreamTrack?: MediaStreamTrack }) => {
      const tracks = remoteTracksByKind.get(kind) ?? [];
      tracks.push(track);
      remoteTracksByKind.set(kind, tracks);
      remoteTrackKinds.add(kind);
    };

    const forgetRemoteTrack = (kind: string, track: { mediaStreamTrack?: MediaStreamTrack }) => {
      const remaining = (remoteTracksByKind.get(kind) ?? []).filter((value) => value !== track);
      if (remaining.length === 0) {
        remoteTracksByKind.delete(kind);
        remoteTrackKinds.delete(kind);
      } else {
        remoteTracksByKind.set(kind, remaining);
      }
    };

    window.liveKitHarness = {
      async connect(ticket: MediaJoinTicket): Promise<void> {
        room = new Room({
          adaptiveStream: false,
          dynacast: false,
        });

        room.on(RoomEvent.TrackSubscribed, (track: { kind: string; attach(): HTMLMediaElement }) => {
          rememberRemoteTrack(track.kind, track);

          const element = track.attach();
          element.autoplay = true;
          element.muted = true;
          element.playsInline = true;
          element.dataset.kind = track.kind;
          document.body.appendChild(element);
        });
        room.on(RoomEvent.TrackUnsubscribed, (track: { kind: string; detach(): HTMLElement[] }) => {
          forgetRemoteTrack(track.kind, track);
          track.detach().forEach((element) => element.remove());
        });

        await room.connect(ticket.serverUrl, ticket.participantToken, { autoSubscribe: true });
      },
      async publishFakeTracks(): Promise<void> {
        if (!room) {
          throw new Error('Room is not connected.');
        }
        await room.localParticipant.setMicrophoneEnabled(true);
        await room.localParticipant.setCameraEnabled(true);
      },
      async hasInboundMedia(): Promise<boolean> {
        const audioTracks = remoteTracksByKind.get('audio') ?? [];
        const videoTracks = remoteTracksByKind.get('video') ?? [];
        const videoElementReady = Array.from(document.querySelectorAll('video')).some((element) => {
          const video = element as HTMLVideoElement;
          return video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA && video.videoWidth > 0 && video.videoHeight > 0;
        });

        return remoteTrackKinds.has('audio') &&
          remoteTrackKinds.has('video') &&
          audioTracks.some((track) => track.mediaStreamTrack?.readyState === 'live') &&
          videoTracks.some((track) => track.mediaStreamTrack?.readyState === 'live') &&
          videoElementReady;
      },
      async disconnect(): Promise<void> {
        await room?.disconnect();
        room = null;
      },
    };
  });
}

async function expectInboundMedia(page: Page): Promise<void> {
  await expect
    .poll(async () => page.evaluate(async () => window.liveKitHarness.hasInboundMedia()))
    .toBe(true);
}

async function startLocalMediaOrigin(): Promise<LocalMediaOrigin> {
  const server = createServer((_request, response) => {
    response.writeHead(200, {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-store',
    });
    response.end('<!doctype html><html><head><title>media e2e</title></head><body></body></html>');
  });

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(mediaOriginPort, mediaOriginHost, () => {
      server.off('error', reject);
      resolve();
    });
  });

  const address = server.address() as AddressInfo;

  return {
    baseUrl: `http://${mediaOriginHost}:${address.port}`,
    close: () => closeServer(server),
  };
}

async function closeServer(server: Server): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    server.close((error) => {
      if (error) {
        reject(error);
      } else {
        resolve();
      }
    });
  });
}

type LocalMediaOrigin = {
  baseUrl: string;
  close(): Promise<void>;
};

async function waitForHttpReady(url: string): Promise<void> {
  await expect
    .poll(
      async () => {
        try {
          const response = await fetch(url);
          return response.status < 500;
        } catch {
          return false;
        }
      },
      { timeout: 180_000 },
    )
    .toBe(true);
}

async function registerUser(prefix: string): Promise<RegisteredUser> {
  const suffix = randomUUID().replaceAll('-', '').slice(0, 10);
  const response = await fetch(`${backendBaseUrl}/api/auth/register`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      username: `${prefix}_${suffix}`,
      displayName: `${prefix} user`,
      email: `${prefix}_${suffix}@example.com`,
      password: 'correct-horse-battery-staple',
      deviceName: 'Playwright',
    }),
  });

  const body = await response.json();
  expect(response.status, JSON.stringify(body)).toBe(200);
  return {
    userId: body.user.id,
    accessToken: body.accessToken,
  };
}

async function createVoiceChannel(ownerUserId: string): Promise<string> {
  const client = new PgClient({ connectionString: postgresUrl });
  const channelId = randomUUID();
  const channelName = `media-e2e-${channelId.slice(0, 8)}`;

  await client.connect();
  try {
    await client.query(
      `
        insert into channels (id, owner_user_id, name, channel_type, is_private)
        values ($1, $2, $3, 'VOICE', false)
      `,
      [channelId, ownerUserId, channelName],
    );
  } finally {
    await client.end();
  }

  return channelId;
}

async function startMediaSession(accessToken: string, channelId: string): Promise<MediaJoinTicket> {
  return graphql<MediaJoinTicket>(
    accessToken,
    `
      mutation StartMediaSession {
        startMediaSession(input: {
          channelId: "${channelId}"
          type: VOICE
          canPublishAudio: true
          canPublishScreen: false
        }) {
          mediaSessionId
          roomName
          serverUrl
          participantToken
        }
      }
    `,
    'startMediaSession',
  );
}

async function joinMediaSession(accessToken: string, mediaSessionId: string): Promise<MediaJoinTicket> {
  return graphql<MediaJoinTicket>(
    accessToken,
    `
      mutation JoinMediaSession {
        joinMediaSession(input: {
          mediaSessionId: "${mediaSessionId}"
          canPublishAudio: false
          canPublishScreen: false
          canSubscribe: true
        }) {
          mediaSessionId
          roomName
          serverUrl
          participantToken
        }
      }
    `,
    'joinMediaSession',
  );
}

async function graphql<T>(
  accessToken: string,
  query: string,
  operationName: string,
): Promise<T> {
  const response = await fetch(`${backendBaseUrl}/graphql`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({ query }),
  });

  const body = await response.json();
  expect(response.status, JSON.stringify(body)).toBe(200);
  expect(body.errors ?? null, JSON.stringify(body)).toBe(null);
  return body.data[operationName] as T;
}

declare global {
  interface Window {
    LivekitClient: {
      Room: new (options?: unknown) => {
        connect(serverUrl: string, token: string, options?: unknown): Promise<void>;
        disconnect(): Promise<void> | void;
        localParticipant: {
          setMicrophoneEnabled(enabled: boolean): Promise<unknown>;
          setCameraEnabled(enabled: boolean): Promise<unknown>;
        };
        on(event: string, listener: (...args: any[]) => void): void;
      };
      RoomEvent: Record<string, string>;
    };
    liveKitHarness: {
      connect(ticket: MediaJoinTicket): Promise<void>;
      publishFakeTracks(): Promise<void>;
      hasInboundMedia(): Promise<boolean>;
      disconnect(): Promise<void>;
    };
  }
}
