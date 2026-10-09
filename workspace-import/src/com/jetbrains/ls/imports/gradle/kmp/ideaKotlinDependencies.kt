// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.kmp

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformModel
import org.jetbrains.kotlin.gradle.idea.proto.tcs.IdeaKotlinClasspathSerializer
import org.jetbrains.kotlin.gradle.idea.proto.tcs.IdeaKotlinDependency
import org.jetbrains.kotlin.gradle.idea.serialize.IdeaKotlinBooleanExtrasSerializer
import org.jetbrains.kotlin.gradle.idea.serialize.IdeaKotlinExtrasSerializationExtension
import org.jetbrains.kotlin.gradle.idea.serialize.IdeaKotlinExtrasSerializer
import org.jetbrains.kotlin.gradle.idea.serialize.IdeaKotlinSerializationContext
import org.jetbrains.kotlin.gradle.idea.serialize.IdeaKotlinSerializationLogger
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency
import org.jetbrains.kotlin.gradle.idea.tcs.extras.KlibExtra
import org.jetbrains.kotlin.gradle.idea.tcs.extras.documentationClasspathKey
import org.jetbrains.kotlin.gradle.idea.tcs.extras.isIdeaProjectLevelKey
import org.jetbrains.kotlin.gradle.idea.tcs.extras.isNativeDistributionKey
import org.jetbrains.kotlin.gradle.idea.tcs.extras.isNativeStdlibKey
import org.jetbrains.kotlin.gradle.idea.tcs.extras.projectArtifactsClasspathKey
import org.jetbrains.kotlin.gradle.idea.tcs.extras.sourcesClasspathKey
import org.jetbrains.kotlin.tooling.core.extrasKeyOf

private val LOG = logger<LspIdeaKotlinSerializationContext>()

/**
 * Decodes the `org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency` protobuf messages of
 * [com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformModel.dependencies], per source set and in KGP order. A
 * message that fails to decode is logged and dropped, as the Kotlin plugin does. The bundled reader
 * (`kotlin-gradle-plugin-idea-proto`) can be older than the KGP that wrote the message; protobuf skips unknown fields.
 */
internal fun KotlinMultiplatformModel.deserializeDependencies(): Map<String, List<IdeaKotlinDependency>> =
    dependencies.mapValues { (_, messages) ->
        messages.mapNotNull { message -> LspIdeaKotlinSerializationContext.IdeaKotlinDependency(message) }
    }

/**
 * The serialization context of the KGP dependency messages, with the same extras as the IDE. An extra without a
 * serializer here is skipped on read, so a newer KGP extra does not break the import.
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.serialize.IntellijIdeaKotlinSerializationContext`,
 * `org.jetbrains.kotlin.idea.gradleJava.configuration.serialize.KotlinGradlePluginExtrasSerializationService`
 */
private object LspIdeaKotlinSerializationContext : IdeaKotlinSerializationContext {
    override val logger: IdeaKotlinSerializationLogger = object : IdeaKotlinSerializationLogger {
        override fun report(severity: IdeaKotlinSerializationLogger.Severity, message: String, cause: Throwable?) {
            // An error here means a broken dependency, not a broken import. The LSP logs it as a warning.
            LOG.warn(message, cause)
        }
    }

    override val extrasSerializationExtension: IdeaKotlinExtrasSerializationExtension = IdeaKotlinExtrasSerializationExtension {
        register(extrasKeyOf<String>("kotlin.debug"), IdeaKotlinExtrasSerializer.javaIoSerializable())
        register(KlibExtra.key, IdeaKotlinExtrasSerializer.javaIoSerializable())
        register(isIdeaProjectLevelKey, IdeaKotlinBooleanExtrasSerializer)
        register(isNativeDistributionKey, IdeaKotlinBooleanExtrasSerializer)
        register(isNativeStdlibKey, IdeaKotlinBooleanExtrasSerializer)
        register(projectArtifactsClasspathKey, IdeaKotlinClasspathSerializer)
        register(sourcesClasspathKey, IdeaKotlinClasspathSerializer)
        register(documentationClasspathKey, IdeaKotlinClasspathSerializer)
    }
}
