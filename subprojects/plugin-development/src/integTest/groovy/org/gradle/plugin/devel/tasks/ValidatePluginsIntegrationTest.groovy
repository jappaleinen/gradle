/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.plugin.devel.tasks

import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.InputArtifactDependencies
import org.gradle.test.fixtures.file.TestFile
import spock.lang.Unroll

import static org.gradle.internal.reflect.validation.Severity.ERROR
import static org.gradle.internal.reflect.validation.Severity.WARNING
import static org.hamcrest.Matchers.containsString

class ValidatePluginsIntegrationTest extends AbstractPluginValidationIntegrationSpec {

    def setup() {
        buildFile << """
            apply plugin: "java-gradle-plugin"
        """
    }

    final String iterableSymbol = '*'

    @Override
    String getNameSymbolFor(String name) {
        ".<name>"
    }

    @Override
    String getKeySymbolFor(String name) {
        '.<key>'
    }

    @Override
    void assertValidationSucceeds() {
        succeeds "validatePlugins"
    }

    @Override
    void assertValidationFailsWith(boolean expectDeprecationsForErrors, List<DocumentedProblem> messages) {
        fails "validatePlugins"
        def report = new TaskValidationReportFixture(file("build/reports/plugin-development/validation-report.txt"))
        report.verify(messages.collectEntries {[(it.message): it.severity ]})

        failure.assertHasCause "Plugin validation failed with ${messages.size()} problem${messages.size()>1?'s':''}"
        messages.forEach { problem ->
            failure.assertThatCause(containsString("$problem.severity: $problem.message"))
        }
    }

    @Override
    TestFile source(String path) {
        return file(path)
    }

    def "supports recursive types"() {
        groovyTaskSource << """
            import org.gradle.api.*
            import org.gradle.api.tasks.*

            class MyTask extends DefaultTask {
                @Nested
                Tree tree

                public static class Tree {
                    @Optional @Nested
                    Tree left

                    @Optional @Nested
                    Tree right

                    String nonAnnotated
                }
            }
        """

        expect:
        assertValidationFailsWith(
            "Type 'MyTask': property 'tree.nonAnnotated' is not annotated with an input or output annotation.": WARNING,
        )
    }

    @Unroll
    def "task cannot have property with annotation @#annotation.simpleName"() {
        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.artifacts.transform.*;

            public class MyTask extends DefaultTask {
                @${annotation.simpleName}
                String getThing() {
                    return null;
                }

                @Nested
                Options getOptions() {
                    return null;
                }

                public static class Options {
                    @${annotation.simpleName}
                    String getNestedThing() {
                        return null;
                    }
                }
            }
        """

        expect:
        assertValidationFailsWith(
            "Type 'MyTask': property 'options.nestedThing' is annotated with invalid property type @${annotation.simpleName}.": ERROR,
            "Type 'MyTask': property 'thing' is annotated with invalid property type @${annotation.simpleName}.": ERROR,
        )

        where:
        annotation << [InputArtifact, InputArtifactDependencies]
    }

    def "can enable stricter validation"() {
        buildFile << """
            dependencies {
                implementation localGroovy()
            }
            def strictProp = providers.gradleProperty("strict").forUseAtConfigurationTime()
            validatePlugins.enableStricterValidation = strictProp.present
        """

        groovyTaskSource << """
            import org.gradle.api.*
            import org.gradle.api.tasks.*

            class MyTask extends DefaultTask {
                @InputFile
                File fileProp

                @InputFiles
                Set<File> filesProp

                @InputDirectory
                File dirProp

                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver fileResolver
            }
        """

        expect:
        assertValidationSucceeds()

        when:
        file("gradle.properties").text = "strict=true"

        then:
        assertValidationFailsWith([
            error("Type 'MyTask': property 'dirProp' is annotated with @InputDirectory but missing a normalization strategy. If you don't declare the normalization, outputs can't be re-used between machines or locations on the same machine, therefore caching efficiency drops significantly. Possible solution: Declare the normalization strategy by annotating the property with either @PathSensitive, @Classpath or @CompileClasspath."),
            error("Type 'MyTask': property 'fileProp' is annotated with @InputFile but missing a normalization strategy. If you don't declare the normalization, outputs can't be re-used between machines or locations on the same machine, therefore caching efficiency drops significantly. Possible solution: Declare the normalization strategy by annotating the property with either @PathSensitive, @Classpath or @CompileClasspath."),
            error("Type 'MyTask': property 'filesProp' is annotated with @InputFiles but missing a normalization strategy. If you don't declare the normalization, outputs can't be re-used between machines or locations on the same machine, therefore caching efficiency drops significantly. Possible solution: Declare the normalization strategy by annotating the property with either @PathSensitive, @Classpath or @CompileClasspath."),
        ])
    }

    def "can validate task classes using external types"() {
        buildFile << """
            ${mavenCentralRepository()}

            dependencies {
                implementation 'com.typesafe:config:1.3.2'
            }
        """

        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import java.io.File;
            import com.typesafe.config.Config;

            public class MyTask extends DefaultTask {
                @Input
                public long getGoodTime() {
                    return 0;
                }

                @Optional @Input
                public Config getConfig() { return null; }

                @TaskAction public void execute() {}
            }
        """

        expect:
        assertValidationSucceeds()
    }

    def "can validate task classes using types from other projects"() {
        settingsFile << """
            include 'lib'
        """

        buildFile << """
            allprojects {
                ${mavenCentralRepository()}
            }

            project(':lib') {
                apply plugin: 'java'

                dependencies {
                    implementation 'com.typesafe:config:1.3.2'
                }
            }

            dependencies {
                implementation project(':lib')
            }
        """

        source("lib/src/main/java/MyUtil.java") << """
            import com.typesafe.config.Config;

            public class MyUtil {
                public Config getConfig() {
                    return null;
                }
            }
        """

        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;

            public class MyTask extends DefaultTask {
                @Input
                public long getGoodTime() {
                    return 0;
                }

                @Input
                public MyUtil getUtil() { return new MyUtil(); }

                @TaskAction public void execute() {}
            }
        """

        expect:
        assertValidationSucceeds()
    }

    def "can validate properties of an artifact transform action"() {
        file("src/main/java/MyTransformAction.java") << """
            import org.gradle.api.*;
            import org.gradle.api.provider.*;
            import org.gradle.api.file.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.artifacts.transform.*;
            import java.io.*;

            public abstract class MyTransformAction implements TransformAction {
                // Should be ignored because it's not a getter
                public void getVoid() {
                }

                // Should be ignored because it's not a getter
                public int getWithParameter(int count) {
                    return count;
                }

                // Ignored because static
                public static int getStatic() {
                    return 0;
                }

                // Ignored because injected
                @javax.inject.Inject
                public abstract org.gradle.api.internal.file.FileResolver getInjected();

                // Valid because it is annotated
                @InputArtifact
                @PathSensitive(PathSensitivity.NONE)
                public abstract Provider<FileSystemLocation> getGoodInput();

                // Invalid because it has no annotation
                public long getBadTime() {
                    return System.currentTimeMillis();
                }

                // Invalid because it has some other annotation
                @Deprecated
                public String getOldThing() {
                    return null;
                }

                // Unsupported annotation
                @InputFile
                public abstract File getInputFile();
            }
        """

        expect:
        assertValidationFailsWith(
            "Type 'MyTransformAction': property 'badTime' is not annotated with an input annotation.": ERROR,
            "Type 'MyTransformAction': property 'inputFile' is annotated with invalid property type @InputFile.": ERROR,
            "Type 'MyTransformAction': property 'oldThing' is not annotated with an input annotation.": ERROR,
        )
    }

    def "can validate properties of an artifact transform parameters object"() {
        file("src/main/java/MyTransformParameters.java") << """
            import org.gradle.api.*;
            import org.gradle.api.file.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.artifacts.transform.*;
            import org.gradle.work.*;
            import java.io.*;

            public interface MyTransformParameters extends TransformParameters {
                // Should be ignored because it's not a getter
                void getVoid();

                // Should be ignored because it's not a getter
                int getWithParameter(int count);

                // Ignored because injected
                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver getInjected();

                // Valid because it is annotated
                @InputFile
                @PathSensitive(PathSensitivity.NONE)
                File getGoodFileInput();

                // Valid
                @Incremental
                @InputFiles
                @PathSensitive(PathSensitivity.NONE)
                FileCollection getGoodIncrementalInput();

                // Valid
                @Input
                String getGoodInput();
                void setGoodInput(String value);

                // Invalid because only file inputs can be incremental
                @Incremental
                @Input
                String getIncrementalNonFileInput();
                void setIncrementalNonFileInput(String value);

                // Invalid because it has no annotation
                long getBadTime();

                // Invalid because it has some other annotation
                @Deprecated
                String getOldThing();

                // Unsupported annotation
                @InputArtifact
                File getInputFile();
            }
        """

        expect:
        assertValidationFailsWith(
            "Type 'MyTransformParameters': property 'badTime' is not annotated with an input annotation.": ERROR,
            "Type 'MyTransformParameters': property 'incrementalNonFileInput' is annotated with @Incremental that is not allowed for @Input properties.": ERROR,
            "Type 'MyTransformParameters': property 'inputFile' is annotated with invalid property type @InputArtifact.": ERROR,
            "Type 'MyTransformParameters': property 'oldThing' is not annotated with an input annotation.": ERROR,
        )
    }

    def "tests only classes from plugin source set"() {
        buildFile << """
            sourceSets {
                plugin {
                    java {
                        srcDir 'src/plugin/java'
                        compileClasspath = configurations.compileClasspath
                    }
                }
            }

            gradlePlugin {
                pluginSourceSet sourceSets.plugin
            }
        """

        file("src/main/java/MainTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;

            public class MainTask extends DefaultTask {
                // WIll not be called out because it's in the main source set
                public long getBadProperty() {
                    return 0;
                }

                @TaskAction public void execute() {}
            }
        """

        file("src/plugin/java/PluginTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;

            public class PluginTask extends DefaultTask {
                // WIll be called out because it's among the plugin's sources
                public long getBadProperty() {
                    return 0;
                }

                @TaskAction public void execute() {}
            }
        """

        expect:
        assertValidationFailsWith(
            "Type 'PluginTask': property 'badProperty' is not annotated with an input or output annotation.": WARNING,
        )
    }
}
