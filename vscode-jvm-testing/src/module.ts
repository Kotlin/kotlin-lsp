// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { testingModule } from '@jetbrains/vscode-testing';
import { JvmTestLanguage } from './jvmTestLanguage';

/** Shows JVM tests in the Testing view and as gutter run icons, once the server can discover them. */
export default testingModule(new JvmTestLanguage());
