// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
class TestMessage {
  constructor(message) {
    this.message = message;
  }

  static diff(message, expected, actual) {
    const result = new TestMessage(message);
    result.expectedOutput = expected;
    result.actualOutput = actual;
    return result;
  }
}

// Real enough for the tests: code under test parses a uri string and later reads it back out.
const Uri = { parse: (value) => ({ toString: () => value, fsPath: value }) };

class Position {
  constructor(line, character) {
    this.line = line;
    this.character = character;
  }
}

class Location {
  constructor(uri, rangeOrPosition) {
    this.uri = uri;
    this.range = rangeOrPosition;
  }
}

class TestMessageStackFrame {
  constructor(label, uri, position) {
    this.label = label;
    this.uri = uri;
    this.position = position;
  }
}

class TestTag {
  constructor(id) {
    this.id = id;
  }
}

class TestCoverageCount {
  constructor(covered, total) {
    this.covered = covered;
    this.total = total;
  }
}

class FileCoverage {
  constructor(uri, statementCoverage, branchCoverage, declarationCoverage) {
    this.uri = uri;
    this.statementCoverage = statementCoverage;
    this.branchCoverage = branchCoverage;
    this.declarationCoverage = declarationCoverage;
  }
}

class StatementCoverage {
  constructor(executed, location, branches = []) {
    this.executed = executed;
    this.location = location;
    this.branches = branches;
  }
}

class BranchCoverage {
  constructor(executed, location, label) {
    this.executed = executed;
    this.location = location;
    this.label = label;
  }
}

class DeclarationCoverage {
  constructor(name, executed, location) {
    this.name = name;
    this.executed = executed;
    this.location = location;
  }
}

// Classes the code under test builds and a test then reads back. The `get` trap has to name them:
// a `module.exports.X = ...` assignment below only makes `X` an importable name, it never reaches a
// reader, because the trap answers every property lookup itself.
const REAL = {
  BranchCoverage,
  DeclarationCoverage,
  FileCoverage,
  Location,
  Position,
  StatementCoverage,
  TestCoverageCount,
  TestMessage,
  TestMessageStackFrame,
  TestTag,
  Uri,
};

function makeStub() {
  const target = function () {};
  const stub = new Proxy(target, {
    get(_t, prop) {
      if (typeof prop === 'string' && prop in REAL) return REAL[prop];
      if (prop === 'prototype') return target.prototype;
      if (prop === Symbol.hasInstance) return () => false;
      if (prop === Symbol.toPrimitive) return () => '';
      return stub;
    },
    apply() {
      return stub;
    },
    construct() {
      return {};
    },
  });
  return stub;
}

module.exports = makeStub();
module.exports.TestMessage = TestMessage;
module.exports.BranchCoverage = BranchCoverage;
module.exports.DeclarationCoverage = DeclarationCoverage;
module.exports.FileCoverage = FileCoverage;
module.exports.StatementCoverage = StatementCoverage;
module.exports.TestCoverageCount = TestCoverageCount;
module.exports.CancellationTokenSource = makeStub();
module.exports.commands = makeStub();
module.exports.ConfigurationTarget = makeStub();
module.exports.CustomExecution = makeStub();
module.exports.debug = makeStub();
module.exports.DebugAdapterDescriptorFactory = makeStub();
module.exports.DebugAdapterServer = makeStub();
module.exports.DebugConfiguration = makeStub();
module.exports.DebugConfigurationProvider = makeStub();
module.exports.DebugSession = makeStub();
module.exports.Diagnostic = makeStub();
module.exports.DiagnosticSeverity = makeStub();
module.exports.EndOfLine = makeStub();
module.exports.EventEmitter = makeStub();
module.exports.extensions = makeStub();
module.exports.ExtensionContext = makeStub();
module.exports.ExtensionMode = makeStub();
module.exports.languages = makeStub();
module.exports.Location = Location;
module.exports.Position = Position;
module.exports.Range = makeStub();
module.exports.RelativePattern = makeStub();
module.exports.Selection = makeStub();
module.exports.SnippetString = makeStub();
module.exports.Task = makeStub();
module.exports.TaskRevealKind = makeStub();
module.exports.TaskScope = makeStub();
module.exports.tasks = makeStub();
module.exports.TestMessageStackFrame = TestMessageStackFrame;
module.exports.TestRunProfileKind = makeStub();
module.exports.TestTag = TestTag;
module.exports.tests = makeStub();
module.exports.TextDocument = makeStub();
module.exports.TextDocumentChangeReason = makeStub();
module.exports.TextDocumentContentProvider = makeStub();
module.exports.Uri = Uri;
module.exports.window = makeStub();
module.exports.workspace = makeStub();
module.exports.WorkspaceFolder = makeStub();
