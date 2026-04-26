import { defineConfig, devices } from '@playwright/test';

const mediaOrigin = 'http://127.0.0.1:4173';

export default defineConfig({
  testDir: './tests',
  timeout: 240_000,
  expect: {
    timeout: 15_000,
  },
  use: {
    ...devices['Desktop Chrome'],
    browserName: 'chromium',
    permissions: ['camera', 'microphone'],
    launchOptions: {
      args: [
        '--autoplay-policy=no-user-gesture-required',
        '--use-fake-device-for-media-stream',
        '--use-fake-ui-for-media-stream',
        `--unsafely-treat-insecure-origin-as-secure=${mediaOrigin}`,
      ],
    },
  },
});
