// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { TestTag } from 'vscode';
import type { TestProfile } from './testLanguage';
import type { TestItemDto } from './testProtocol';

export class TestProfileTags {
  private readonly byProfile = new Map<string, TestTag>();
  private readonly anyNode: readonly TestTag[];
  private readonly taggedNode = new Map<string, TestTag>();

  constructor(profiles: readonly TestProfile[]) {
    const anyNode: TestTag[] = [];
    for (const profile of profiles) {
      const tag = new TestTag(profile.id);
      this.byProfile.set(profile.id, tag);
      switch (profile.nodes) {
        case 'any':
          anyNode.push(tag);
          break;
        case 'tagged':
          this.taggedNode.set(profile.id, tag);
          break;
      }
    }
    this.anyNode = anyNode;
  }

  of(profile: TestProfile): TestTag {
    const tag = this.byProfile.get(profile.id);
    if (!tag) throw new Error(`Unknown test profile '${profile.id}'`);
    return tag;
  }

  get ofGroup(): TestTag[] {
    return [...this.anyNode];
  }

  ofNode(dto: TestItemDto): TestTag[] {
    const tagged = (dto.tags ?? []).flatMap((id) => this.taggedNode.get(id) ?? []);
    return [...this.anyNode, ...tagged];
  }
}
