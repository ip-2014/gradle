/*
 * Copyright 2015 the original author or authors.
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
package org.gradle.tooling.internal.consumer.parameters;

import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.tooling.Failure;
import org.gradle.tooling.events.test.*;
import org.gradle.tooling.events.test.internal.DefaultTestFinishEvent;
import org.gradle.tooling.events.test.internal.DefaultTestStartEvent;
import org.gradle.tooling.internal.consumer.DefaultFailure;
import org.gradle.tooling.internal.protocol.*;

import java.util.*;

/**
 * Converts progress events sent from the tooling provider to the tooling client to the corresponding event types available on the public Tooling API, and broadcasts the converted events to the
 * matching progress listeners. This adapter handles all the different incoming progress event types (except the original logging-derived progress listener).
 */
class BuildProgressListenerAdapter implements BuildProgressListenerVersion1 {

    private final ListenerBroadcast<TestProgressListener> testProgressListeners = new ListenerBroadcast<TestProgressListener>(TestProgressListener.class);
    private final Map<Object, TestOperationDescriptor> testDescriptorCache = new HashMap<Object, TestOperationDescriptor>();

    BuildProgressListenerAdapter(List<TestProgressListener> testListeners) {
        this.testProgressListeners.addAll(testListeners);
    }

    @Override
    public List<String> getSubscribedOperations() {
        return this.testProgressListeners.isEmpty() ? Collections.<String>emptyList() : Collections.singletonList(BuildProgressListenerVersion1.TEST_EXECUTION);
    }

    @Override
    public void onEvent(final Object event) {
        if (event instanceof TestProgressEventVersion1) {
            broadcastTestProgressEvent((TestProgressEventVersion1) event);
        }
    }

    private void broadcastTestProgressEvent(TestProgressEventVersion1 event) {
        TestProgressEvent testProgressEvent = toTestProgressEvent(event);
        if (testProgressEvent != null) {
            testProgressListeners.getSource().statusChanged(testProgressEvent);
        }
    }

    private synchronized TestProgressEvent toTestProgressEvent(final TestProgressEventVersion1 event) {
        if (event instanceof TestStartedProgressEventVersion1) {
            return testStartedEvent((TestStartedProgressEventVersion1) event);
        } else if (event instanceof TestFinishedProgressEventVersion1) {
            return testFinishedEvent((TestFinishedProgressEventVersion1) event);
        } else {
            return null;
        }
    }

    private TestStartEvent testStartedEvent(TestStartedProgressEventVersion1 event) {
        long eventTime = event.getEventTime();
        String displayName = event.getDisplayName();
        TestOperationDescriptor testDescriptor = addTestDescriptor(event.getDescriptor());
        return new DefaultTestStartEvent(eventTime, displayName, testDescriptor);
    }

    private TestFinishEvent testFinishedEvent(final TestFinishedProgressEventVersion1 event) {
        long eventTime = event.getEventTime();
        String displayName = event.getDisplayName();
        TestOperationDescriptor testDescriptor = removeTestDescriptor(event.getDescriptor());
        TestOperationResult result = toTestResult(event.getResult());
        return new DefaultTestFinishEvent(eventTime, displayName, testDescriptor, result);
    }

    private TestOperationDescriptor addTestDescriptor(TestDescriptorVersion1 testDescriptor) {
        TestOperationDescriptor cachedTestDescriptor = this.testDescriptorCache.get(testDescriptor.getId());
        if (cachedTestDescriptor != null) {
            throw new IllegalStateException(String.format("Operation %s already available.", toString(testDescriptor)));
        }
        final TestOperationDescriptor parent = getParentTestDescriptor(testDescriptor);
        TestOperationDescriptor newTestDescriptor = toTestDescriptor(testDescriptor, parent);
        testDescriptorCache.put(testDescriptor.getId(), newTestDescriptor);
        return newTestDescriptor;
    }

    private TestOperationDescriptor removeTestDescriptor(TestDescriptorVersion1 testDescriptor) {
        TestOperationDescriptor cachedTestDescriptor = this.testDescriptorCache.remove(testDescriptor.getId());
        if (cachedTestDescriptor == null) {
            throw new IllegalStateException(String.format("Operation %s is not available.", toString(testDescriptor)));
        }
        return cachedTestDescriptor;
    }

    private static TestOperationDescriptor toTestDescriptor(final TestDescriptorVersion1 testDescriptor, final TestOperationDescriptor parent) {
        if (testDescriptor instanceof JvmTestDescriptorVersion1) {
            final JvmTestDescriptorVersion1 jvmTestDescriptor = (JvmTestDescriptorVersion1) testDescriptor;
            return new JvmTestOperationDescriptor() {
                @Override
                public String getName() {
                    return jvmTestDescriptor.getName();
                }

                @Override
                public String getDisplayName() {
                    return jvmTestDescriptor.getDisplayName();
                }

                @Override
                public JvmTestKind getJvmTestKind() {
                    return toJvmTestKind(jvmTestDescriptor);
                }

                @Override
                public String getSuiteName() {
                    return jvmTestDescriptor.getSuiteName();
                }

                @Override
                public String getClassName() {
                    return jvmTestDescriptor.getClassName();
                }

                @Override
                public String getMethodName() {
                    return jvmTestDescriptor.getMethodName();
                }

                @Override
                public TestOperationDescriptor getParent() {
                    return parent;
                }

                @Override
                public String toString() {
                    return getDisplayName();
                }
            };
        } else {
            return new TestOperationDescriptor() {
                @Override
                public String getName() {
                    return testDescriptor.getName();
                }

                @Override
                public String getDisplayName() {
                    return testDescriptor.getDisplayName();
                }

                @Override
                public TestOperationDescriptor getParent() {
                    return parent;
                }

                @Override
                public String toString() {
                    return getDisplayName();
                }
            };
        }
    }

    private static JvmTestKind toJvmTestKind(JvmTestDescriptorVersion1 jvmTestDescriptor) {
        String jvmTestKind = jvmTestDescriptor.getTestKind();
        if (JvmTestDescriptorVersion1.KIND_SUITE.equals(jvmTestKind)) {
            return JvmTestKind.SUITE;
        } else if (JvmTestDescriptorVersion1.KIND_ATOMIC.equals(jvmTestKind)) {
            return JvmTestKind.ATOMIC;
        } else {
            return JvmTestKind.UNKNOWN;
        }
    }

    private TestOperationResult toTestResult(final TestResultVersion1 result) {
        if (result.getResultType().equals(TestResultVersion1.RESULT_SUCCESSFUL)) {
            return new TestSuccessResult() {
                @Override
                public long getStartTime() {
                    return result.getStartTime();
                }

                @Override
                public long getEndTime() {
                    return result.getEndTime();
                }
            };
        } else if (result.getResultType().equals(TestResultVersion1.RESULT_SKIPPED)) {
            return new TestSkippedResult() {
                @Override
                public long getStartTime() {
                    return result.getStartTime();
                }

                @Override
                public long getEndTime() {
                    return result.getEndTime();
                }
            };
        } else if (result.getResultType().equals(TestResultVersion1.RESULT_FAILED)) {
            return new TestFailureResult() {
                @Override
                public long getStartTime() {
                    return result.getStartTime();
                }

                @Override
                public long getEndTime() {
                    return result.getEndTime();
                }

                @Override
                public List<? extends Failure> getFailures() {
                    return toFailures(result);
                }
            };
        } else {
            return null;
        }
    }

    private static List<Failure> toFailures(TestResultVersion1 testResult) {
        List<Failure> failures = new ArrayList<Failure>();
        for (FailureVersion1 origFailure : testResult.getFailures()) {
            failures.add(toFailure(origFailure));
        }
        return failures;
    }

    private static Failure toFailure(FailureVersion1 origFailure) {
        return origFailure == null ? null : new DefaultFailure(
                origFailure.getMessage(),
                origFailure.getDescription(),
                toFailure(origFailure.getCauses()));
    }

    private static List<Failure> toFailure(List<? extends FailureVersion1> causes) {
        List<Failure> result = new ArrayList<Failure>();
        for (FailureVersion1 cause : causes) {
            result.add(toFailure(cause));
        }
        return result;
    }

    private TestOperationDescriptor getParentTestDescriptor(TestDescriptorVersion1 testDescriptor) {
        Object parentId = testDescriptor.getParentId();
        if (parentId == null) {
            return null;
        } else {
            TestOperationDescriptor parentTestDescriptor = testDescriptorCache.get(parentId);
            if (parentTestDescriptor == null) {
                throw new IllegalStateException(String.format("Parent test descriptor with id %s not available for %s.", parentId, toString(testDescriptor)));
            } else {
                return parentTestDescriptor;
            }
        }
    }

    private static String toString(TestDescriptorVersion1 testDescriptor) {
        if (testDescriptor instanceof JvmTestDescriptorVersion1) {
            return String.format("TestOperationDescriptor[id(%s), name(%s), className(%s), parent(%s)]",
                    testDescriptor.getId(), testDescriptor.getName(), ((JvmTestDescriptorVersion1) testDescriptor).getClassName(), testDescriptor.getParentId());
        } else {
            return String.format("TestOperationDescriptor[id(%s), name(%s), parent(%s)]", testDescriptor.getId(), testDescriptor.getName(), testDescriptor.getParentId());
        }
    }

}
