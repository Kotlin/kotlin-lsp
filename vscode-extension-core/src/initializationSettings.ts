// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

/**
 * Settings-derived parts of the server's `InitializeOptions`, which it decodes as one object: a
 * single ill-typed value there makes it discard every other field too, so each is sanitized before
 * it is sent. Keys contributed by modules are not covered here; the server decodes those per key.
 */

/**
 * An externally configured project passed to the server via initialization options.
 * Mirrors the `intellij.projects` setting and the server-side `ConfiguredProject` model.
 */
export interface ConfiguredProject {
  /**
   * Build tool / project type, e.g. "gradle", "maven", "bazel", "json". The special value "subproject" makes
   * this entry a pure pointer to a nested project directory whose own `.vscode/settings.json`
   * `intellij.projects` are resolved recursively by the server.
   */
  type: string;
  /** URI pointing to the project's build file or workspace root (or, for "subproject", its directory). */
  path: string;
  /** Maven only: extra environment variables for the import process. */
  env?: Record<string, string>;
  /** Maven only: JVM system properties for the import process. */
  'system-properties'?: Record<string, string>;
  /** Maven and Gradle: path to the JDK home used to run the import. */
  'java-home'?: string;
  /** Bazel only: path to the Bazel project file, relative to the workspace root. */
  'project-path'?: string;
}

export interface BuiltinInitializationOptions {
  defaultSdk: string | undefined;
  buildTools: Record<string, string>;
  projects: ConfiguredProject[];
  disableRocksDBWriteAheadLog: boolean;
}

export interface SettingProblem {
  setting: string;
  /** Self-contained sentence, naming the setting the ignored value came from. */
  message: string;
}

export interface Sanitized<T> {
  value: T;
  problems: SettingProblem[];
}

function problem(setting: string, reason: string): SettingProblem {
  return { setting, message: `\`${setting}\` ${reason}` };
}

/** A file whose save can change any of these settings: user, workspace, or folder settings. */
export function isSettingsDocument(path: string): boolean {
  return path.endsWith('/settings.json') || path.endsWith('.code-workspace');
}

export type SettingsChangeAction = 'none' | 'start' | 'restart' | 'reload';

/**
 * How a settings change reaches the server. Launch settings become process arguments and
 * environment, so only a new process picks them up; `initializationOptions` are resent by
 * `intellij/reloadWorkspace` on the live connection.
 */
export function settingsChangeAction(change: {
  affectsLaunch: boolean;
  affectsInitializationOptions: boolean;
  serverRunning: boolean;
}): SettingsChangeAction {
  if (!change.affectsLaunch && !change.affectsInitializationOptions) return 'none';
  if (!change.serverRunning) return 'start';
  return change.affectsLaunch ? 'restart' : 'reload';
}

const REQUIRED_STRING_FIELDS = ['type', 'path'] as const;
const OPTIONAL_STRING_FIELDS = ['java-home', 'project-path'] as const;
const OPTIONAL_STRING_MAP_FIELDS = ['env', 'system-properties'] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isStringMap(value: unknown): boolean {
  return isRecord(value) && Object.values(value).every((entry) => typeof entry === 'string');
}

function projectRejectionReason(entry: unknown): string | undefined {
  if (!isRecord(entry)) return 'must be an object';
  for (const field of REQUIRED_STRING_FIELDS) {
    const value = entry[field];
    if (typeof value !== 'string' || value.trim() === '') {
      return `requires a non-empty "${field}"`;
    }
  }
  for (const field of OPTIONAL_STRING_FIELDS) {
    if (entry[field] !== undefined && typeof entry[field] !== 'string') {
      return `"${field}" must be a string`;
    }
  }
  for (const field of OPTIONAL_STRING_MAP_FIELDS) {
    if (entry[field] !== undefined && !isStringMap(entry[field])) {
      return `"${field}" must be an object with string values`;
    }
  }
  return undefined;
}

export function sanitizeConfiguredProjects(
  setting: string,
  value: unknown,
): Sanitized<ConfiguredProject[]> {
  if (value === undefined || value === null) return { value: [], problems: [] };
  if (!Array.isArray(value)) return { value: [], problems: [problem(setting, 'must be an array')] };

  const projects: ConfiguredProject[] = [];
  const problems: SettingProblem[] = [];
  value.forEach((entry, index) => {
    const reason = projectRejectionReason(entry);
    if (reason === undefined) projects.push(entry as ConfiguredProject);
    else problems.push(problem(setting, `entry #${index} ${reason}`));
  });
  return { value: projects, problems };
}

export function sanitizeOptionalString(
  setting: string,
  value: unknown,
): Sanitized<string | undefined> {
  if (value === undefined || value === null) return { value: undefined, problems: [] };
  if (typeof value === 'string') return { value, problems: [] };
  return { value: undefined, problems: [problem(setting, 'must be a string')] };
}

export function sanitizeBoolean(setting: string, value: unknown): Sanitized<boolean> {
  if (value === undefined || value === null) return { value: false, problems: [] };
  if (typeof value === 'boolean') return { value, problems: [] };
  return { value: false, problems: [problem(setting, 'must be a boolean')] };
}

/** Per-folder build tool overrides, keyed by folder URI. Folders without one are left out. */
export function sanitizeBuildTools(
  setting: string,
  entries: readonly (readonly [string, unknown])[],
): Sanitized<Record<string, string>> {
  const buildTools: Record<string, string> = {};
  const problems: SettingProblem[] = [];
  for (const [uri, buildTool] of entries) {
    if (typeof buildTool === 'string') buildTools[uri] = buildTool;
    else if (buildTool !== undefined && buildTool !== null) {
      problems.push(problem(setting, `for ${uri} must be a string`));
    }
  }
  return { value: buildTools, problems };
}
