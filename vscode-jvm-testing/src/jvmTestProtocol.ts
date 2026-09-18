export const JvmTestCommands = {
  /** One document → its test classes and their methods. */
  discoverTestsInFile: 'intellij.jvm.discoverTestsInFile',
  /** Names of the modules a workspace scan should ask about. */
  discoverTestModules: 'intellij.jvm.discoverTestModules',
  /** One module → its test classes, without their methods. */
  discoverTestsInModule: 'intellij.jvm.discoverTestsInModule',
  /** A set of tests → what it takes to launch them, one process per framework among them. */
  resolveTestLaunch: 'intellij.jvm.resolveTestLaunch',
} as const;

/**
 * The runtime paths a JVM launch of a file's module runs with. Owned by `dap/`
 * (`LSResolveLaunchCommandDescriptorProvider`), not by the testing providers — listed here only because a
 * test launch needs the classpath out of it.
 */
export const RESOLVE_LAUNCH_COMMAND = 'intellij.java.resolveLaunch';

/**
 * Mirrors the server's `JvmTestItem`. A null field never travels: the server's JSON has
 * `explicitNulls = false`, so the client gets no field at all.
 *
 * The `id` is opaque here: only the server reads it, and it reads it back in `resolveTestLaunch`.
 */
export interface JvmTestItemDto {
  id: string;
  kind: 'CLASS' | 'METHOD';
  displayName: string;
  uri: string;
  range: {
    start: { line: number; character: number };
    end: { line: number; character: number };
  };
  parentId?: string | null;
  location?: string;
  className: string;
  moduleName?: string | null;
  /**
   * False when no run of this node is possible: an abstract class, or a framework the server has no
   * runner for. Absent from an older server, which knew of no such node.
   */
  runnable?: boolean;
}

/** Mirrors the server's `UniqueTestNode`: a node the runner named itself, and the class it is in. */
export interface JvmUniqueTestNode {
  className: string;
  uniqueId: string;
}

/** Mirrors the server's `TestLaunchRequest`. */
export interface JvmTestLaunchRequest {
  testIds: string[];
  uniqueIds: JvmUniqueTestNode[];
}

/** Mirrors the server's `TestLaunch`: one process to start. */
export interface JvmTestLaunch {
  mainClass: string;
  args: string[];
  runtimeClasspath: string[];
}
