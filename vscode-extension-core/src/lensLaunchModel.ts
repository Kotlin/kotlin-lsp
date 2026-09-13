// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/**
 * The Run/Debug lens with the editor taken out: which configuration type a lens launch uses, from the server's
 * `intellij.java.resolveBuildToolLaunch` answer. It stays out of `dap.ts` so a test can use it without `vscode`.
 */

/** The part of the server's answer the lens reads; `BuildToolLaunchResponse` in `dap.ts` carries the rest. */
export interface LensBuildToolAnswer {
  tool?: string;
  /** Why the owning tool refused the launch, when `tool` is absent because it refused rather than never launches. */
  reason?: string;
}

/** The configuration type to start, or the reason the launch stops here. */
export type LensLaunchDecision = { type: string } | { refused: string };

/**
 * The decision of the lens for [answer], with [typeByTool] mapping a tool id to its configuration type and [jvmType]
 * the plain JVM type.
 *
 * A named tool launches through its own type; a tool without one falls back to the JVM. A tool that refused the
 * launch and said why stops the lens: the JVM fallback would run the class outside the tool, and the reason is what
 * the user needs to see. No tool and no reason means no tool launches this module, so the JVM does. A failure to ask
 * (`undefined`) keeps the JVM fallback too: that path resolves everything again and reports properly.
 */
export function lensLaunchDecision(
  answer: LensBuildToolAnswer | undefined,
  typeByTool: Record<string, string>,
  jvmType: string,
): LensLaunchDecision {
  if (answer === undefined) return { type: jvmType };
  if (answer.tool !== undefined) return { type: typeByTool[answer.tool] ?? jvmType };
  if (answer.reason !== undefined) return { refused: answer.reason };
  return { type: jvmType };
}
