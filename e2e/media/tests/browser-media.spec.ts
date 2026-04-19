import { expect, Page, test } from '@playwright/test';
import { createServer, Server } from 'node:http';
import { AddressInfo } from 'node:net';

type SessionDescriptionInit = {
  sdp: string;
  type: RTCSdpType;
};

type IceCandidateInit = RTCIceCandidateInit;

type ClientDescription = {
  description: SessionDescriptionInit;
  candidates: IceCandidateInit[];
};

test('fake audio and video tracks flow between two browser clients', async ({ browser }) => {
  const origin = await startLocalMediaOrigin();
  const context = await browser.newContext();
  await context.grantPermissions(['camera', 'microphone'], { origin: origin.baseUrl });

  const publisher = await context.newPage();
  const subscriber = await context.newPage();

  try {
    await installRtcHarness(publisher, origin.baseUrl);
    await installRtcHarness(subscriber, origin.baseUrl);

    const offer = await publisher.evaluate(async () => window.rtc.createOfferWithFakeMedia());
    const answer = await subscriber.evaluate(async (remoteOffer) => {
      return window.rtc.answer(remoteOffer);
    }, offer);

    await publisher.evaluate(async (remoteAnswer) => {
      await window.rtc.acceptAnswer(remoteAnswer);
    }, answer);
    await subscriber.evaluate(async (publisherCandidates) => {
      await window.rtc.addIceCandidates(publisherCandidates);
    }, offer.candidates);

    await expectInboundMedia(subscriber);
  } finally {
    await context.close();
    await origin.close();
  }
});

async function installRtcHarness(page: Page, baseUrl: string): Promise<void> {
  await page.goto(baseUrl);
  await page.evaluate(() => {
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error(
        `getUserMedia is unavailable at ${window.location.href}; secureContext=${window.isSecureContext}`,
      );
    }

    const candidates: IceCandidateInit[] = [];
    const remoteTrackKinds: string[] = [];
    const pc = new RTCPeerConnection({ iceServers: [] });

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        candidates.push(event.candidate.toJSON());
      }
    };
    pc.ontrack = (event) => {
      remoteTrackKinds.push(event.track.kind);
    };

    const waitForIceGathering = async () => {
      if (pc.iceGatheringState === 'complete') {
        return;
      }
      await new Promise<void>((resolve) => {
        const onStateChange = () => {
          if (pc.iceGatheringState === 'complete') {
            pc.removeEventListener('icegatheringstatechange', onStateChange);
            resolve();
          }
        };
        pc.addEventListener('icegatheringstatechange', onStateChange);
      });
    };

    window.rtc = {
      async createOfferWithFakeMedia(): Promise<ClientDescription> {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
        stream.getTracks().forEach((track) => pc.addTrack(track, stream));

        await pc.setLocalDescription(await pc.createOffer());
        await waitForIceGathering();

        return {
          description: pc.localDescription!.toJSON() as SessionDescriptionInit,
          candidates: [...candidates],
        };
      },
      async answer(remoteOffer: ClientDescription): Promise<ClientDescription> {
        await pc.setRemoteDescription(remoteOffer.description);
        for (const candidate of remoteOffer.candidates) {
          await pc.addIceCandidate(candidate);
        }

        await pc.setLocalDescription(await pc.createAnswer());
        await waitForIceGathering();

        return {
          description: pc.localDescription!.toJSON() as SessionDescriptionInit,
          candidates: [...candidates],
        };
      },
      async acceptAnswer(remoteAnswer: ClientDescription): Promise<void> {
        await pc.setRemoteDescription(remoteAnswer.description);
        for (const candidate of remoteAnswer.candidates) {
          await pc.addIceCandidate(candidate);
        }
      },
      async addIceCandidates(remoteCandidates: IceCandidateInit[]): Promise<void> {
        for (const candidate of remoteCandidates) {
          await pc.addIceCandidate(candidate);
        }
      },
      async hasInboundMedia(): Promise<boolean> {
        const stats = await pc.getStats();
        let inboundBytes = 0;
        let inboundPackets = 0;

        stats.forEach((report) => {
          const inbound = report as {
            type: string;
            isRemote?: boolean;
            bytesReceived?: number;
            packetsReceived?: number;
          };

          if (inbound.type === 'inbound-rtp' && !inbound.isRemote) {
            inboundBytes += inbound.bytesReceived ?? 0;
            inboundPackets += inbound.packetsReceived ?? 0;
          }
        });

        return remoteTrackKinds.includes('audio') &&
          remoteTrackKinds.includes('video') &&
          (inboundBytes > 0 || inboundPackets > 0);
      },
    };
  });
}

async function expectInboundMedia(page: Page): Promise<void> {
  await expect
    .poll(async () => page.evaluate(async () => window.rtc.hasInboundMedia()))
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
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject);
      resolve();
    });
  });

  const address = server.address() as AddressInfo;

  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
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

declare global {
  interface Window {
    rtc: {
      createOfferWithFakeMedia(): Promise<ClientDescription>;
      answer(remoteOffer: ClientDescription): Promise<ClientDescription>;
      acceptAnswer(remoteAnswer: ClientDescription): Promise<void>;
      addIceCandidates(remoteCandidates: IceCandidateInit[]): Promise<void>;
      hasInboundMedia(): Promise<boolean>;
    };
  }
}
