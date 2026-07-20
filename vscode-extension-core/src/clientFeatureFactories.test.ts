import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { FeatureState, StaticFeature } from 'vscode-languageclient/node';
import { startClientWithFeatures } from './clientFeatureFactories';

test('registers a fresh client feature before each helper-managed client start', async () => {
  const events: string[] = [];
  const features: StaticFeature[] = [];

  const createFeature = (): StaticFeature => {
    const feature: StaticFeature = {
      fillClientCapabilities: () => {},
      initialize: () => {},
      getState: (): FeatureState => ({ kind: 'static' }),
      clear: () => {},
    };
    features.push(feature);
    events.push('factory');
    return feature;
  };

  const createClient = () => ({
    registerFeature(): void {
      events.push('register');
    },
    async start(): Promise<void> {
      events.push('start');
    },
  });

  // doStartLspClient is the sole production caller, so recreating a client exercises its feature lifecycle boundary.
  const firstClient = createClient();
  await startClientWithFeatures(firstClient, [createFeature]);
  const secondClient = createClient();
  await startClientWithFeatures(secondClient, [createFeature]);

  assert.deepEqual(events, ['factory', 'register', 'start', 'factory', 'register', 'start']);
  assert.equal(features.length, 2);
  assert.notStrictEqual(features[0], features[1]);
});
