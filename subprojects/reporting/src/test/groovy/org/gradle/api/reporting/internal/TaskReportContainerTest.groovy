/*
 * Copyright 2009 the original author or authors.
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

package org.gradle.api.reporting.internal

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.reporting.Report
import org.gradle.api.tasks.Nested
import org.gradle.internal.reflect.Instantiator
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.Unroll

class TaskReportContainerTest extends Specification {

    final Project project = ProjectBuilder.builder().build()
    final TestTask task = project.task("testTask", type: TestTask)
    def container = createContainer {
        dir("b")
        file("a")
    }

    static class TestTask extends DefaultTask {
        @Nested
        TaskReportContainer<Report> reports
    }

    static class TestReportContainer extends TaskReportContainer<Report> {
        TestReportContainer(Task task, Closure c) {
            super(Report, task)

            c.delegate = new Object() {
                Report file(String name) {
                    add(TaskGeneratedReport, name, Report.OutputType.FILE, task)
                }
                Report dir(String name) {
                    add(TaskGeneratedReport, name, Report.OutputType.DIRECTORY, task)
                }
            }

            c()
        }
    }

    DefaultReportContainer createContainer(Closure c) {
        def container = project.services.get(Instantiator).newInstance(TestReportContainer, task, c)
        container.all {
            it.enabled true
            destination project.file(it.name)
        }
        task.reports = container
        return container
    }

    List<File> getOutputFiles(Task task = task) {
        task.outputs.files.files.toList().sort()
    }

    List<String> getInputPropertyValue() {
        task.inputs.properties["reports.enabledReportNames"] as List<String>
    }

    @Unroll("tasks inputs and outputs are wired correctly A: #aEnabled, B: #bEnabled")
    def "tasks inputs and outputs are wired correctly"() {
        when:
        container.a.enabled = aEnabled
        container.b.enabled = bEnabled

        then:
        outputFiles*.name == fileNames
        inputPropertyValue == names

        where:
        aEnabled | bEnabled | fileNames  | names
        true     | true     | ["a", "b"] | ["a", "b"]
        true     | false    | ["a"]      | ["a"]
        false    | false    | []         | []
    }
}
