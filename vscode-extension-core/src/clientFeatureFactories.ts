import type { StaticFeature } from 'vscode-languageclient/node';

export type ClientFeatureFactory = () => StaticFeature;

interface FeatureClient {
  registerFeature(feature: StaticFeature): void;
  start(): Promise<void>;
}

export async function startClientWithFeatures(
  client: FeatureClient,
  featureFactories: readonly ClientFeatureFactory[],
): Promise<void> {
  for (const createFeature of featureFactories) {
    client.registerFeature(createFeature());
  }
  await client.start();
}
