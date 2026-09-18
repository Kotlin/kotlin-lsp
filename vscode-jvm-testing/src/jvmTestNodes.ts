import type { TestItem, Uri } from 'vscode';
import type { JvmTestLaunchGroup } from './jvmTestPlan';
import type { JvmTestTree } from './jvmTestTree';
import type { MessageAttributes } from './testReportStream';

export interface JvmTestNodes {
  readonly locate: (attributes: MessageAttributes) => TestItem | undefined;
  readonly fileOf: (className: string) => Uri | undefined;
}

export function jvmTestNodes(tree: JvmTestTree, group: JvmTestLaunchGroup): JvmTestNodes {
  const { moduleName } = group;
  const itemsByNodeId = new Map<string, TestItem>();
  const takenLocations = new Set<string>();

  const remember = (nodeId: string | undefined, item: TestItem): TestItem => {
    if (nodeId !== undefined) itemsByNodeId.set(nodeId, item);
    return item;
  };

  return {
    locate: (attributes) => {
      const nodeId = attributes.nodeId ?? attributes.id;
      const known = nodeId === undefined ? undefined : itemsByNodeId.get(nodeId);
      if (known) return known;

      const location = attributes.locationHint;
      const taken = nodeId !== undefined && location !== undefined && takenLocations.has(location);
      if (location !== undefined && !taken) {
        const item = tree.itemAtLocation({ moduleName, location });
        if (item) {
          if (nodeId !== undefined) takenLocations.add(location);
          return remember(nodeId, item);
        }
      }

      if (nodeId === undefined) return undefined;
      const parentNodeId = attributes.parentNodeId;
      const parent = parentNodeId === undefined ? undefined : itemsByNodeId.get(parentNodeId);
      if (!parent) return undefined;
      const item = tree.ensureRuntimeItem({
        parent,
        nodeId,
        displayName: attributes.name ?? nodeId,
      });
      return item && remember(nodeId, item);
    },
    fileOf: (className) => tree.fileOfClass({ moduleName, className }),
  };
}
