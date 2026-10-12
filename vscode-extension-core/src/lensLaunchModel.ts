// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/**
 * The Run/Debug lens with the editor taken out: the launch configuration a lens starts, from the arguments the
 * server put on the lens. It stays out of `dap.ts` so a test can use it without `vscode`.
 */

/** The arguments of the server's lens that the configuration is made of. */
export interface LensArgs {
  mainClass: string;
  uri?: string;
  /** The build tool of the module of [uri], as the server names it, or absent for a module without one. */
  tool?: string;
}

/** A launch configuration, as `debug.startDebugging` takes it. */
export interface LensLaunchConfig {
  type: string;
  request: 'launch';
  name: string;
  mainClass: string;
  file?: string;
  tool?: string;
}

/**
 * The configuration of a lens launch for [args]: the type of the tool in [typeByTool], else [jvmType]; the file
 * of the lens as a path through [toPath]; the tool as the server named it, so the server knows the lens asked for
 * nothing else. Nothing is asked of the server: the launch request carries everything it needs.
 */
export function lensLaunchConfig(
  args: LensArgs,
  typeByTool: Record<string, string>,
  jvmType: string,
  toPath: (uri: string) => string,
): LensLaunchConfig {
  const config: LensLaunchConfig = {
    type: (args.tool !== undefined ? typeByTool[args.tool] : undefined) ?? jvmType,
    request: 'launch',
    name: args.mainClass.split('.').pop() ?? 'Run main',
    mainClass: args.mainClass,
  };
  if (args.uri) config.file = toPath(args.uri);
  if (args.tool !== undefined) config.tool = args.tool;
  return config;
}
