/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(EGExtension.class)
class KernelTest {
    private static final String EXPECTED_CONFIG_OUTPUT =
            "---\n"
            + "services:\n"
            + "  service1:\n"
            + "    dependencies: []\n"
            + "    lifecycle:\n"
            + "      run:\n"
            + "        script: \"test script\"\n"
            + "  main:\n"
            + "    dependencies:\n"
            + "    - \"service1\"\n";

    @TempDir
    protected Path tempRootDir;
    private Kernel kernel;

    @BeforeEach
    void beforeEach() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        kernel = new Kernel();
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown(2);
    }

    @Test
    void GIVEN_kernel_and_services_WHEN_orderedDependencies_THEN_dependencies_are_returned_in_order()
            throws InputValidationException {
        KernelLifecycle kernelLifecycle = spy(new KernelLifecycle(kernel, new KernelCommandLine(kernel)));
        kernel.setKernelLifecycle(kernelLifecycle);

        EvergreenService mockMain =
                new EvergreenService(kernel.getConfig()
                        .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "main"));
        when(kernelLifecycle.getMain()).thenReturn(mockMain);

        EvergreenService service1 =
                new EvergreenService(kernel.getConfig()
                        .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "service1"));
        EvergreenService service2 =
                new EvergreenService(kernel.getConfig()
                        .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "service2"));

        List<EvergreenService> od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(1));
        assertEquals(mockMain, od.get(0));

        mockMain.addOrUpdateDependency(service1, State.RUNNING, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(2));

        assertEquals(service1, od.get(0));
        assertEquals(mockMain, od.get(1));

        mockMain.addOrUpdateDependency(service2, State.RUNNING, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(3));

        // Since service 1 and 2 are equal in the tree, they may come back as either position 1 or 2
        assertThat(od.get(0), anyOf(is(service1), is(service2)));
        assertThat(od.get(1), anyOf(is(service1), is(service2)));
        assertEquals(mockMain, od.get(2));

        service1.addOrUpdateDependency(service2, State.RUNNING, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(3));

        // Now that 2 is a dependency of 1, there is a strict order required
        assertEquals(service2, od.get(0));
        assertEquals(service1, od.get(1));
        assertEquals(mockMain, od.get(2));
    }

    @Test
    void GIVEN_kernel_and_services_WHEN_orderedDependencies_with_a_cycle_THEN_no_dependencies_returned()
            throws InputValidationException {
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        EvergreenService mockMain =
                new EvergreenService(kernel.getConfig()
                        .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "main"));
        when(kernelLifecycle.getMain()).thenReturn(mockMain);

        EvergreenService service1 =
                new EvergreenService(kernel.getConfig()
                        .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "service1"));

        // Introduce a dependency cycle
        service1.addOrUpdateDependency(mockMain, State.RUNNING, false);
        mockMain.addOrUpdateDependency(service1, State.RUNNING, false);

        List<EvergreenService> od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(0));
    }

    @Test
    void GIVEN_kernel_with_services_WHEN_writeConfig_THEN_service_config_written_to_file()
            throws Exception {
        kernel.parseArgs();

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        EvergreenService mockMain =
                new EvergreenService(kernel.getConfig()
                        .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "main"));
        when(kernelLifecycle.getMain()).thenReturn(mockMain);
        EvergreenService service1 =
                new EvergreenService(kernel.getConfig()
                        .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "service1"));

        // Add dependency on service1 to main
        mockMain.addOrUpdateDependency(service1, State.RUNNING, false);
        ((List<String>) kernel.findServiceTopic("main").findLeafChild("dependencies").getOnce())
                .add("service1");
        kernel.findServiceTopic("service1")
                .lookup(EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC, "run", "script")
                .withValue("test script");

        StringWriter writer = new StringWriter();
        kernel.writeConfig(writer);
        assertEquals(EXPECTED_CONFIG_OUTPUT, writer.toString());

        kernel.writeEffectiveConfig();
        String readFile = new String(Files.readAllBytes(kernel.getConfigPath().resolve("effectiveConfig.evg")),
                StandardCharsets.UTF_8);
        assertEquals(EXPECTED_CONFIG_OUTPUT, readFile);
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_definition_in_config_THEN_create_GenericExternalService()
            throws Exception {
        Configuration config = kernel.getConfig();
        config.lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, "1",
                EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC);

        EvergreenService main = kernel.locate("1");
        assertEquals("1", main.getName());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_WHEN_locate_finds_class_definition_in_config_THEN_create_service(ExtensionContext context)
            throws Exception {
        // We need to launch the kernel here as this triggers EZPlugins to search the classpath for @ImplementsService
        // it complains that there's no main, but we don't care for this test
        ignoreExceptionUltimateCauseWithMessage(context, "No matching definition in system model for: main");
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        Configuration config = kernel.getConfig();
        config.lookup(EvergreenService.SERVICES_NAMESPACE_TOPIC, "1", "class")
                .withValue(TestClass.class.getName());

        EvergreenService main = kernel.locate("1");
        assertEquals("tester", main.getName());

        EvergreenService service2 = kernel.locate("testImpl");
        assertEquals("testImpl", service2.getName());
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_classname_but_not_class_THEN_throws_ServiceLoadException() {
        String badClassName = TestClass.class.getName()+"lkajsdklajglsdj";

        Configuration config = kernel.getConfig();
        config.lookup(EvergreenService.SERVICES_NAMESPACE_TOPIC, "2", "class")
                .withValue(badClassName);

        ServiceLoadException ex = assertThrows(ServiceLoadException.class, () -> kernel.locate("2"));
        assertEquals("Can't load service class from " + badClassName, ex.getMessage());
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_no_definition_in_config_THEN_throws_ServiceLoadException() {
        ServiceLoadException ex = assertThrows(ServiceLoadException.class, () -> kernel.locate("5"));
        assertEquals("No matching definition in system model for: 5", ex.getMessage());
    }

    static class TestClass extends EvergreenService {
        public TestClass(Topics topics) {
            super(topics);
        }

        @Override
        public String getName() {
            return "tester";
        }
    }

    @ImplementsService(name = "testImpl")
    static class TestImplementor extends EvergreenService {
        public TestImplementor(Topics topics) {
            super(topics);
        }

        @Override
        public String getName() {
            return "testImpl";
        }
    }
}
