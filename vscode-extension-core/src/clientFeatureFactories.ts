import type { LanguageClient, StaticFeature } from 'vscode-languageclient/node';

/**
 * Creates a feature of one language client. The factory gets that client, so a feature can tell
 * the client of each initialize from the client of another window start.
 */
export type ClientFeatureFactory = (client: LanguageClient) => StaticFeature;

interface FeatureClient {
  registerFeature(feature: StaticFeature): void;
  start(): Promise<void>;
}

export async function startClientWithFeatures<C extends FeatureClient>(
  client: C,
  featureFactories: readonly ((client: C) => StaticFeature)[],
): Promise<void> {
  for (const createFeature of featureFactories) {
    client.registerFeature(createFeature(client));
  }
  await client.start();
}
