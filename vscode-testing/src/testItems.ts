// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { TestItem } from 'vscode';

export function leafTests(item: TestItem): TestItem[] {
  const leaves: TestItem[] = [];
  const visit = (node: TestItem): void => {
    let childless = true;
    node.children.forEach((child) => {
      childless = false;
      visit(child);
    });
    if (childless) leaves.push(node);
  };
  visit(item);
  return leaves;
}
