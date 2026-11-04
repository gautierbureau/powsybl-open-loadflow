/*
 * Copyright (c) 2023-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.commons.test.PowsyblTestReportResourceBundle;
import com.powsybl.contingency.Contingency;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.PilotPoint;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControl;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControlAdder;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.loadflow.LoadFlowRunParameters;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ServiceParameterResolver;
import com.powsybl.openloadflow.ac.outerloop.SecondaryVoltageControlOuterLoop;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfNetworkParameters;
import com.powsybl.openloadflow.network.LfSecondaryVoltageControl;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import com.powsybl.openloadflow.sa.OpenSecurityAnalysisProvider;
import com.powsybl.openloadflow.util.report.PowsyblOpenLoadFlowReportResourceBundle;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisRunParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static com.powsybl.openloadflow.util.LoadFlowAssert.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
@ExtendWith(ServiceParameterResolver.class)
class SecondaryVoltageControlTest {

    private static final double TARGET_V_SATURATION = 14.5;
    private static final String COMPONENT_VS_SCALING_ITERATION_COUNT = "29/15";
    private static final String STALL_VS_BLOCKED_ITERATION_COUNT = "8/6";
    private static final double PINNED_TEST_BOUND = 1.06;
    private static final String PINNED_TEST_EXPECTED = "5/12451/12720/21200 4/12452/12720/21200";

    private final CommonTestConfig commonTestConfig;

    SecondaryVoltageControlTest(CommonTestConfig commonTestConfig) {
        this.commonTestConfig = commonTestConfig;
    }

    private Network network;

    private Bus b4;
    private Bus b6;
    private Bus b8;
    private Bus b10;
    private Generator g6;
    private Generator g8;

    private LoadFlow.Runner loadFlowRunner;

    private LoadFlowRunParameters loadFlowRunParameters;

    private LoadFlowParameters parameters;

    private OpenLoadFlowParameters parametersExt;

    @BeforeEach
    void setUp() {
        network = IeeeCdfNetworkFactory.create14();
        b4 = network.getBusBreakerView().getBus("B4");
        b6 = network.getBusBreakerView().getBus("B6");
        b8 = network.getBusBreakerView().getBus("B8");
        b10 = network.getBusBreakerView().getBus("B10");
        g6 = network.getGenerator("B6-G");
        g8 = network.getGenerator("B8-G");

        // adjust reactive limit to avoid generators be to limit
        g8.newMinMaxReactiveLimits()
                .setMinQ(-6)
                .setMaxQ(200)
                .add();

        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(commonTestConfig.matrixFactory()));
        parameters = new LoadFlowParameters();
        parametersExt = OpenLoadFlowParameters.create(parameters)
                .setMaxPlausibleTargetVoltage(1.6);
        loadFlowRunParameters = new LoadFlowRunParameters().setParameters(parameters);
    }

    private static double qToK(Generator g) {
        ReactiveLimits limits = g.getReactiveLimits();
        double q = -g.getTerminal().getQ();
        double p = -g.getTerminal().getP();
        return (2 * q - limits.getMaxQ(p) - limits.getMinQ(p))
                / (limits.getMaxQ(p) - limits.getMinQ(p));
    }

    @Test
    void testNoReactiveLimits() {
        parameters.setUseReactiveLimits(false);
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();
        SecondaryVoltageControl control = network.getExtension(SecondaryVoltageControl.class);
        PilotPoint pilotPoint = control.getControlZone("z1").orElseThrow().getPilotPoint();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(3, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(12.611, b10);
        assertVoltageEquals(12.84, b6);
        assertVoltageEquals(21.8, b8);
        assertEquals(0.248, qToK(g6), DELTA_POWER);
        assertEquals(-0.77, qToK(g8), DELTA_POWER);

        parametersExt.setSecondaryVoltageControl(true);

        result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(6, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(13, b10);
        assertVoltageEquals(12.945, b6);
        assertVoltageEquals(23.839, b8);
        assertEquals(-0.412, qToK(g6), DELTA_POWER);
        assertEquals(-0.412, qToK(g8), DELTA_POWER);

        pilotPoint.setTargetV(13.5);
        result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(8, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(13.5, b10);
        assertVoltageEquals(13.358, b6);
        assertVoltageEquals(25.621, b8);
        assertEquals(-0.094, qToK(g6), DELTA_POWER);
        assertEquals(-0.094, qToK(g8), DELTA_POWER);

        pilotPoint.setTargetV(12);
        result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(6, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(12, b10);
        assertVoltageEquals(12.151, b6);
        assertVoltageEquals(20.269, b8);
        assertEquals(-0.932, qToK(g6), DELTA_POWER);
        assertEquals(-0.932, qToK(g8), DELTA_POWER);
    }

    @Test
    void testReactiveLimits() {
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(11.5).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(3, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(12.606, b10);
        assertVoltageEquals(12.84, b6);
        assertVoltageEquals(21.8, b8);
        assertReactivePowerEquals(-12.730, g6.getTerminal()); // [-6, 24]
        assertReactivePowerEquals(-17.623, g8.getTerminal()); // [-6, 200]

        parametersExt.setSecondaryVoltageControl(true);

        result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(7, result.getComponentResults().get(0).getIterationCount());

        assertVoltageEquals(11.817, b10); // 11.5 kV was not feasible
        assertVoltageEquals(11.986, b6);
        assertVoltageEquals(19.8, b8);
        assertReactivePowerEquals(6, g6.getTerminal()); // [-6, 24] => qmin
        assertReactivePowerEquals(3.150, g8.getTerminal()); // [-6, 200], not saturated after clipped correction
    }

    void modifyNetworkToUnblockGeneratorFromLimit() {
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(15).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        // Start g6 and g8 close to qmin so the SVC correction first interacts with reactive limit switching.
        g6.setLocalTargetV(11.8);
        g8.setLocalTargetV(19.5);
    }

    @Test
    void testReEnableHelpfulControllerBusesFromReactiveLimit() throws IOException {
        modifyNetworkToUnblockGeneratorFromLimit();
        // This scenario works if the slack distribution fails and an injection is added at the slack bus
        parametersExt.setPlausibleActivePowerLimit(5000); // Remove large generators from slack distribution
        parametersExt.setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior.LEAVE_ON_SLACK_BUS);

        parametersExt.setSecondaryVoltageControl(true);

        ReportNode node = ReportNode.newRootReportNode()
                .withResourceBundles(PowsyblOpenLoadFlowReportResourceBundle.BASE_NAME, PowsyblTestReportResourceBundle.TEST_BASE_NAME)
                .withMessageTemplate("test")
                .build();

        // SVC must be able to re-enable a controller bus disabled by reactive limits when the zone still has another
        // available controller. With the clipped correction, the controllers finally reach the opposite reactive limit.
        loadFlowRunParameters.setReportNode(node);
        var result = loadFlowRunner.run(network, loadFlowRunParameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(24, result.getComponentResults().get(0).getIterationCount());

        assertVoltageEquals(15, b10);
        assertVoltageEquals(14.604, b6);
        assertVoltageEquals(30.744, b8);
        assertReactivePowerEquals(-24, g6.getTerminal()); // [-6, 24] => qmax
        assertReactivePowerEquals(-200, g8.getTerminal()); // [-6, 200] => qmax

        // Note that slack distribution fails in this test that runs with the LEAVE_ON_SLACK_BUS slack failure behaviour
        String expected = """
                + test
                   + Load flow on network 'ieee14cdf'
                      + Network CC0 SC0
                         2 generators have been discarded from active power control because of maxP not plausible
                         + Network info
                            Network has 14 buses and 20 branches
                            Network balance: active generation=272.4 MW, active load=259 MW, reactive generation=0 MVar, reactive load=73.5 MVar
                            Angle reference bus: VL1_0
                            Slack bus: VL1_0
                         Voltage initialization with method Uniform Values
                         Outer loop DistributedSlack
                         Outer loop SecondaryVoltageControl
                         + Outer loop ReactiveLimits
                            + Outer loop iteration 2
                               + 1 buses switched PV -> PQ (4 buses remain PV)
                                  Switch bus 'VL6_0' PV -> PQ, q=59.989159 > maxQ=24
                         Outer loop DistributedSlack
                         Outer loop SecondaryVoltageControl
                         + Outer loop ReactiveLimits
                            + Outer loop iteration 4
                               + 2 buses switched PV -> PQ (3 buses remain PV)
                                  Switch bus 'VL3_0' PV -> PQ, q=-0.477767 < minQ=0
                                  Switch bus 'VL6_0' PV -> PQ, q=122.625173 > maxQ=24
                            + Outer loop iteration 5
                               + 1 buses switched PQ -> PV (0 buses blocked PQ due to the max number of switches)
                                  Switch bus 'VL3_0' PQ -> PV, q=minQ and v=134.067233kV < targetV=136.35kV
                         Outer loop DistributedSlack
                         Outer loop SecondaryVoltageControl
                         + Outer loop ReactiveLimits
                            + Outer loop iteration 8
                               + 2 buses switched PV -> PQ (3 buses remain PV)
                                  Switch bus 'VL3_0' PV -> PQ, q=-8.506687 < minQ=0
                                  Switch bus 'VL6_0' PV -> PQ, q=59.686055 > maxQ=24
                         + Outer loop DistributedSlack
                            + Outer loop iteration 9
                               Failed to distribute slack bus active power mismatch, 1.087632 MW remains
                         Outer loop SecondaryVoltageControl
                         + Outer loop ReactiveLimits
                            + Outer loop iteration 11
                               + 3 buses switched PV -> PQ (1 buses remain PV)
                                  Switch bus 'VL2_0' PV -> PQ, q=-48.226659 < minQ=-40
                                  Switch bus 'VL6_0' PV -> PQ, q=25.896434 > maxQ=24
                                  Switch bus 'VL8_0' PV -> PQ, q=200.819982 > maxQ=200
                         + Outer loop DistributedSlack
                            + Outer loop iteration 12
                               Failed to distribute slack bus active power mismatch, 3.012286 MW remains
                         Outer loop SecondaryVoltageControl
                         AC load flow completed successfully (solverStatus=CONVERGED, outerloopStatus=STABLE)
                """;

        assertReportEquals(new ByteArrayInputStream(expected.getBytes()), node);
    }

    @Test
    void testSecurityAnalysisWithReEnabledControllerBus() {
        modifyNetworkToUnblockGeneratorFromLimit();

        // Pilot point is set to test SVC re-enabling controller buses without them turning back PQ
        network.getExtension(SecondaryVoltageControl.class)
                .getControlZone("z1").orElseThrow().getPilotPoint().setTargetV(14.95);

        parametersExt.setPlausibleActivePowerLimit(5000); // Remove large generators from slack distribution
        parametersExt.setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior.LEAVE_ON_SLACK_BUS);
        parametersExt.setSecondaryVoltageControl(true);

        List<Contingency> contingencies = List.of(Contingency.branch("L1-2-1"), Contingency.branch("L1-5-1"));
        OpenSecurityAnalysisProvider securityAnalysisProvider = new OpenSecurityAnalysisProvider(commonTestConfig.matrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
        assertDoesNotThrow(() -> securityAnalysisProvider.run(network,
                network.getVariantManager().getWorkingVariantId(),
                n -> contingencies,
                new SecurityAnalysisRunParameters().
                        setSecurityAnalysisParameters(new SecurityAnalysisParameters().setLoadFlowParameters(parameters))).join());
    }

    @Test
    void testBlockedReactiveLimitsPqPvSwitchDoesNotPreventSvcReEnable() throws IOException {
        modifyNetworkToUnblockGeneratorFromLimit();

        parametersExt.setSecondaryVoltageControl(true);
        // Blocks PQ->PV moves in ReactiveLimits. SVC may still re-enable helpful controller buses before computing its
        // correction, which is the behavior this test protects.
        parametersExt.setReactiveLimitsMaxPqPvSwitch(0);

        ReportNode node = ReportNode.newRootReportNode()
                .withResourceBundles(PowsyblOpenLoadFlowReportResourceBundle.BASE_NAME, PowsyblTestReportResourceBundle.TEST_BASE_NAME)
                .withMessageTemplate("test")
                .build();

        loadFlowRunParameters.setReportNode(node);
        var result = loadFlowRunner.run(network, loadFlowRunParameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(25, result.getComponentResults().get(0).getIterationCount());

        assertVoltageEquals(15, b10);
        assertVoltageEquals(14.604, b6);
        assertVoltageEquals(30.744, b8);
        assertReactivePowerEquals(-24, g6.getTerminal()); // [-6, 24] => qmax
        assertReactivePowerEquals(-200, g8.getTerminal()); // [-6, 200] => qmax

        String expected = """
                + test
                   + Load flow on network 'ieee14cdf'
                      + Network CC0 SC0
                         + Network info
                            Network has 14 buses and 20 branches
                            Network balance: active generation=272.4 MW, active load=259 MW, reactive generation=0 MVar, reactive load=73.5 MVar
                            Angle reference bus: VL1_0
                            Slack bus: VL1_0
                         Voltage initialization with method Uniform Values
                         Outer loop DistributedSlack
                         Outer loop SecondaryVoltageControl
                         + Outer loop ReactiveLimits
                            + Outer loop iteration 2
                               + 1 buses switched PV -> PQ (4 buses remain PV)
                                  Switch bus 'VL6_0' PV -> PQ, q=59.989159 > maxQ=24
                         Outer loop DistributedSlack
                         Outer loop SecondaryVoltageControl
                         + Outer loop ReactiveLimits
                            + Outer loop iteration 4
                               + 2 buses switched PV -> PQ (3 buses remain PV)
                                  Switch bus 'VL3_0' PV -> PQ, q=-0.477767 < minQ=0
                                  Switch bus 'VL6_0' PV -> PQ, q=122.625173 > maxQ=24
                            + Outer loop iteration 5
                               + 0 buses switched PQ -> PV (1 buses blocked PQ due to the max number of switches)
                                  Bus 'VL3_0' blocked PQ as it has reached its max number of PQ -> PV switch (1)
                         Outer loop DistributedSlack
                         Outer loop SecondaryVoltageControl
                         + Outer loop ReactiveLimits
                            + Outer loop iteration 7
                               + 1 buses switched PV -> PQ (3 buses remain PV)
                                  Switch bus 'VL6_0' PV -> PQ, q=59.183534 > maxQ=24
                         + Outer loop DistributedSlack
                            + Outer loop iteration 8
                               Slack bus active power (1.087638 MW) distributed in 1 distribution iteration(s)
                         Outer loop SecondaryVoltageControl
                         + Outer loop ReactiveLimits
                            + Outer loop iteration 11
                               + 3 buses switched PV -> PQ (1 buses remain PV)
                                  Switch bus 'VL2_0' PV -> PQ, q=-48.439612 < minQ=-40
                                  Switch bus 'VL6_0' PV -> PQ, q=25.942836 > maxQ=24
                                  Switch bus 'VL8_0' PV -> PQ, q=200.747667 > maxQ=200
                         + Outer loop DistributedSlack
                            + Outer loop iteration 12
                               Slack bus active power (1.89492 MW) distributed in 1 distribution iteration(s)
                         Outer loop SecondaryVoltageControl
                         Outer loop ReactiveLimits
                         AC load flow completed successfully (solverStatus=CONVERGED, outerloopStatus=STABLE)
                """;

        assertReportEquals(new ByteArrayInputStream(expected.getBytes()), node);
    }

    @Test
    void multiNoReactiveLimitsZonesTest() {
        parameters.setUseReactiveLimits(false);
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(142).withBusbarSectionsOrBusesIds(List.of("B4")).add()
                .newControlUnit().withId("B1-G").add()
                .newControlUnit().withId("B2-G").add()
                .newControlUnit().withId("B3-G").add()
                .add()
                .newControlZone()
                .withName("z2")
                .newPilotPoint().withTargetV(14.5).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        parametersExt.setSecondaryVoltageControl(true);
        var result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(16, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(142, b4);
        assertVoltageEquals(14.5, b10);
    }

    @Test
    void testOpenBranchIssue() {
        // open branch L6-13-1 on side 2 so that a neighbor branch of B6-G is disconnected to the other side
        network.getLine("L6-13-1").getTerminal2().disconnect();

        parameters.setUseReactiveLimits(false);
        parametersExt.setSecondaryVoltageControl(true);

        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(14.4).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        var result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(6, result.getComponentResults().get(0).getIterationCount());
        // This non-local topology issue still converges, but the clipped SVC correction no longer reaches the pilot
        // target in one run.
        assertVoltageEquals(13.503, b10);
        assertVoltageEquals(14.093, b6);
        assertVoltageEquals(23.8, b8);
    }

    @Test
    void pilotPointNotFoundTest() {
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("XX", "YY")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add();

        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        List<LfNetwork> lfNetworks = LfNetwork.load(network, new LfNetworkLoaderImpl(), networkParameters);
        LfNetwork lfNetwork = lfNetworks.get(0);
        assertTrue(lfNetwork.getSecondaryVoltageControls().isEmpty());
    }

    @Test
    void controlUnitNotFoundTest() {
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B99-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        List<LfNetwork> lfNetworks = LfNetwork.load(network, new LfNetworkLoaderImpl(), networkParameters);
        LfNetwork lfNetwork = lfNetworks.get(0);
        List<LfSecondaryVoltageControl> secondaryVoltageControls = lfNetwork.getSecondaryVoltageControls();
        assertEquals(1, secondaryVoltageControls.size());
        assertEquals(1, secondaryVoltageControls.get(0).getControlledBuses().size()); // B99-G not found
    }

    @Test
    void testUnsupportedControlUnitIsDiscarded() {
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B9-SH").add() // this is a shunt which is not supported
                .add()
                .add();

        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        List<LfNetwork> lfNetworks = LfNetwork.load(network, new LfNetworkLoaderImpl(), networkParameters);
        LfNetwork lfNetwork = lfNetworks.get(0);
        List<LfSecondaryVoltageControl> secondaryVoltageControls = lfNetwork.getSecondaryVoltageControls();
        assertEquals(1, secondaryVoltageControls.size());
        assertEquals(1, secondaryVoltageControls.get(0).getControlUnits().size());
        assertEquals("B6-G", secondaryVoltageControls.get(0).getControlUnits().get(0).getId());
    }

    @Test
    void testControlUnitWithoutVoltageControlIsDiscarded() {
        // reactive range is so small that B6-G is discarded from voltage control by the generic voltage control
        // checks, so it cannot be a control unit of the zone at all
        Generator g6 = network.getGenerator("B6-G");
        g6.newMinMaxReactiveLimits()
                .setMinQ(100)
                .setMaxQ(100.000001)
                .add();

        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        parametersExt.setSecondaryVoltageControl(true);

        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        List<LfNetwork> lfNetworks = LfNetwork.load(network, new LfNetworkLoaderImpl(), networkParameters);
        LfNetwork lfNetwork = lfNetworks.get(0);
        List<LfSecondaryVoltageControl> secondaryVoltageControls = lfNetwork.getSecondaryVoltageControls();
        assertEquals(1, secondaryVoltageControls.size());
        assertEquals(1, secondaryVoltageControls.get(0).getControlUnits().size());
        assertEquals("B8-G", secondaryVoltageControls.get(0).getControlUnits().get(0).getId());

        assertDoesNotThrow(() -> loadFlowRunner.run(network, parameters));
    }

    @Test
    void testLocalControlUnitParticipatesWithoutEquivalentLocalTargetV() {
        // B6-G and B8-G control the voltage of their own bus: they already have a target voltage for it, so they do
        // not need any equivalent local target voltage to participate to the secondary voltage control

        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        LfSecondaryVoltageControl svc = LfNetwork.load(network, new LfNetworkLoaderImpl(), networkParameters)
                .get(0).getSecondaryVoltageControls().get(0);

        assertEquals(2, svc.getControlUnits().size());
        assertTrue(controlUnit(svc, "B6-G").isParticipate());
        assertTrue(controlUnit(svc, "B8-G").isParticipate());
    }

    @Test
    void testZoneWithoutAnyRemainingControlUnitIsNotCreated() {
        // the only control unit of the zone is a shunt, which is not supported: no secondary voltage control is created
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B9-SH").add()
                .add()
                .add();

        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), networkParameters).get(0);
        assertTrue(lfNetwork.getSecondaryVoltageControls().isEmpty());
    }

    @Test
    void testControlUnitWithTooSmallSecondaryVoltageControlReactiveRangeDoesNotParticipate() {
        Generator g6 = network.getGenerator("B6-G");
        g6.newMinMaxReactiveLimits()
                .setMinQ(100)
                .setMaxQ(102)
                .add();

        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").withParticipate(true).add()
                .newControlUnit().withId("B8-G").withParticipate(true).add()
                .add()
                .add();

        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        LfSecondaryVoltageControl secondaryVoltageControl = LfNetwork.load(network, new LfNetworkLoaderImpl(), networkParameters)
                .get(0)
                .getSecondaryVoltageControls()
                .get(0);

        assertEquals(2, secondaryVoltageControl.getControlUnits().size());
        assertFalse(secondaryVoltageControl.getControlUnits().stream()
                .filter(controlUnit -> controlUnit.getId().equals("B6-G"))
                .findFirst()
                .orElseThrow()
                .isParticipate());
    }

    @Test
    void disjointControlZoneTest() {
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B99-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .newControlZone()
                .withName("z2")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B1-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        LfNetworkLoaderImpl networkLoader = new LfNetworkLoaderImpl();
        PowsyblException e = assertThrows(PowsyblException.class, () -> LfNetwork.load(network, networkLoader, networkParameters));
        assertEquals("Generator voltage control of controlled bus 'VL8_0' is present in more that one control zone", e.getMessage());
    }

    @Test
    void testFarPilotPointTargetVoltageChangeIsClipped() {
        // g8 generator is very far from pilot point bus b12. The secondary voltage control target voltage change is
        // clipped, so the load-flow converges instead of failing on an implausible target voltage.
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(11.5).withBusbarSectionsOrBusesIds(List.of("B12")).add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        parametersExt.setSecondaryVoltageControl(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(8, result.getComponentResults().get(0).getIterationCount());
    }

    @Test
    void testStalledZoneIsDetected() {
        // Pilot point target is far out of reach of the only control unit of the zone: its controlled bus target
        // voltage gets pinned to the plausible range bound, so the same ineffective correction would be requested at
        // each outer loop iteration. The stalled zone has to be detected and ignored, otherwise the outer loop would
        // run until max iteration is reached.
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(20).withBusbarSectionsOrBusesIds(List.of("B12")).add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        parameters.setUseReactiveLimits(false);
        parametersExt.setSecondaryVoltageControl(true)
                .setMaxPlausibleTargetVoltage(1.1);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        // B8 target voltage is pinned to the 1.1 pu plausible bound and the pilot point target remains out of reach
        assertVoltageEquals(22.0, b8);
        // the zone has been given up quickly instead of being adjusted until max outer loop iteration
        assertTrue(result.getComponentResults().get(0).getIterationCount() < 10,
                "SVC outer loop should stop early on a stalled zone");
    }

    @Test
    void testTargetVClippedToPlausibleRange() {
        // Same far pilot point as above, but with a tighter plausibility range. The first clipped SVC correction would
        // move B8 target voltage below 1.0 pu, so the SVC outer loop clips it to the plausible bound.
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(11.5).withBusbarSectionsOrBusesIds(List.of("B12")).add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        parameters.setUseReactiveLimits(false);
        parametersExt.setSecondaryVoltageControl(true)
                .setMinPlausibleTargetVoltage(1.0);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(5, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(20.0, b8);
    }

    @Test
    void testWithGeneratorRemoteVoltage() {
        // move generator 6 to bus 5 but keep voltage control on bus 6
        network.getGenerator("B6-G").remove();
        var g5 = network.getVoltageLevel("VL5").newGenerator()
                .setId("B5-G")
                .setBus("B5")
                .setTargetP(0)
                .setMinP(-9999)
                .setMaxP(9999)
                .newVoltageRegulation()
                    .withMode(RegulationMode.VOLTAGE)
                    .withTargetValue(12.8)
                    .withRegulating(true)
                    .withTerminal(network.getLoad("B6-L").getTerminal())
                    .add()
                .add();
        parameters.setUseReactiveLimits(false);
        parametersExt.setSecondaryVoltageControl(true);
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B5-G").add() // this control unit is a generator with remote voltage control
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        // B5-G has no equivalent local target voltage: it does not participate to the secondary voltage control and
        // its remote voltage control is kept (B6 stays at its 12.8 kV target), so only B8-G is adjusted.
        assertEquals(6, result.getComponentResults().get(0).getIterationCount());

        assertVoltageEquals(13, b10);
        assertVoltageEquals(12.8, b6);
        assertVoltageEquals(24.622, b8);
        assertReactivePowerEquals(40.875, g5.getTerminal());
        assertReactivePowerEquals(-76.332, g8.getTerminal());

        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        LfSecondaryVoltageControl svc = LfNetwork.load(network, new LfNetworkLoaderImpl(), networkParameters)
                .get(0).getSecondaryVoltageControls().get(0);
        assertFalse(controlUnit(svc, "B5-G").isParticipate()); // remote control without equivalent local target voltage
        assertTrue(controlUnit(svc, "B8-G").isParticipate());
        assertEquals("VL6_0", controlledBusId(svc, "B5-G")); // remote voltage control has been kept
    }

    @Test
    void testWithGeneratorRemoteVoltageAndPartialEquivalentLocalTargetV() {
        // Two units of the same zone have a remote voltage control, but only one of them defines an equivalent local
        // target voltage: only that one is shifted to local control, the other one keeps its remote control.
        network.getGenerator("B6-G").remove();
        network.getVoltageLevel("VL5").newGenerator()
                .setId("B5-G") // remote voltage control of B6, without equivalent local target voltage
                .setBus("B5")
                .setTargetP(0)
                .setMinP(-9999)
                .setMaxP(9999)
                .newVoltageRegulation()
                    .withMode(RegulationMode.VOLTAGE)
                    .withTargetValue(12.8)
                    .withRegulating(true)
                    .withTerminal(network.getLoad("B6-L").getTerminal())
                    .add()
                .add();
        var g7 = network.getVoltageLevel("VL7").newGenerator()
                .setId("B7-G") // remote voltage control of B9, with an equivalent local target voltage
                .setBus("B7")
                .setTargetP(0)
                .setMinP(-9999)
                .setMaxP(9999)
                .newVoltageRegulation()
                    .withMode(RegulationMode.VOLTAGE)
                    .withTargetValue(12.4)
                    .withRegulating(true)
                    .withTerminal(network.getLoad("B9-L").getTerminal())
                    .add()
                .add();
        g7.setLocalTargetV(14.2);

        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B5-G").add()
                .newControlUnit().withId("B7-G").add()
                .add()
                .add();

        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        LfSecondaryVoltageControl secondaryVoltageControl = LfNetwork.load(network, new LfNetworkLoaderImpl(), networkParameters)
                .get(0)
                .getSecondaryVoltageControls()
                .get(0);

        assertEquals(2, secondaryVoltageControl.getControlUnits().size());
        // B5-G keeps its remote voltage control of B6
        assertEquals("VL6_0", controlledBusId(secondaryVoltageControl, "B5-G"));
        // B7-G has been shifted to a local voltage control of its own bus, using its equivalent local target voltage
        assertEquals("VL7_0", controlledBusId(secondaryVoltageControl, "B7-G"));
        assertEquals(14.2 / 14.0, targetV(secondaryVoltageControl, "B7-G"), DELTA_V);
    }

    private static LfSecondaryVoltageControl.ControlUnit controlUnit(LfSecondaryVoltageControl svc, String id) {
        return svc.getControlUnits().stream()
                .filter(controlUnit -> controlUnit.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static String controlledBusId(LfSecondaryVoltageControl svc, String id) {
        return controlUnit(svc, id).getGeneratorVoltageControl().getControlledBus().getId();
    }

    private static double targetV(LfSecondaryVoltageControl svc, String id) {
        return controlUnit(svc, id).getGeneratorVoltageControl().getTargetValue();
    }

    @Test
    void testWithDiscardedGenerator() {
        g6.newMinMaxReactiveLimits()
                .setMinQ(100)
                .setMaxQ(100.00000001)
                .add();
        parametersExt.setSecondaryVoltageControl(true);
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(5, result.getComponentResults().get(0).getIterationCount());

        assertVoltageEquals(13, b10);
        assertVoltageEquals(13.090, b6);
        assertVoltageEquals(23.381, b8);
    }

    @Test
    void testWithGeneratorSharedRemoteVoltage() {
        // remove generator 6 and create 2 new generators controlling voltage on bus 6
        network.getGenerator("B6-G").remove();

        var s5 = network.getSubstation("S5");
        var vl99 = s5.newVoltageLevel()
                .setId("VL99")
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .setNominalV(6)
                .add();
        var b991 = vl99.getBusBreakerView().newBus()
                .setId("B99-1")
                .add();
        var b992 = vl99.getBusBreakerView().newBus()
                .setId("B99-2")
                .add();
        var g991 = vl99.newGenerator()
                .setId("B99-G1")
                .setBus("B99-1")
                .setTargetP(1)
                .setMinP(-1000)
                .setMaxP(1000)
                .newVoltageRegulation()
                    .withMode(RegulationMode.VOLTAGE)
                    .withTargetValue(12.8)
                    .withRegulating(true)
                    .withTerminal(network.getLoad("B6-L").getTerminal())
                    .add()
                .add();
        g991.newMinMaxReactiveLimits()
                .setMinQ(-30)
                .setMaxQ(70)
                .add();
        var g992 = vl99.newGenerator()
                .setId("B99-G2")
                .setBus("B99-2")
                .setTargetP(2)
                .setMinP(-1000)
                .setMaxP(1000)
                .newVoltageRegulation()
                    .withMode(RegulationMode.VOLTAGE)
                    .withTargetValue(12.8)
                    .withRegulating(true)
                    .withTerminal(network.getLoad("B6-L").getTerminal())
                    .add()
                .add();
        g992.newMinMaxReactiveLimits()
                .setMinQ(-40)
                .setMaxQ(50)
                .add();
        s5.newTwoWindingsTransformer()
                .setId("B99-T1")
                .setVoltageLevel1("VL99")
                .setBus1("B99-1")
                .setVoltageLevel2("VL6")
                .setBus2("B6")
                .setRatedU1(5)
                .setRatedU2(12)
                .setR(0.1)
                .setX(1)
                .add();
        s5.newTwoWindingsTransformer()
                .setId("B99-T2")
                .setVoltageLevel1("VL99")
                .setBus1("B99-2")
                .setVoltageLevel2("VL6")
                .setBus2("B6")
                .setRatedU1(5)
                .setRatedU2(12)
                .setR(0.2)
                .setX(2)
                .add();

        parametersExt.setSecondaryVoltageControl(true);
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(12.5).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B99-G1").add()
                .newControlUnit().withId("B99-G2").add()
                .add()
                .add();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        // B99-G1 and B99-G2 share a remote voltage control of B6 and have no equivalent local target voltage, so they
        // are discarded from the zone: the pilot bus is left at its natural voltage instead of its 12.5 kV target.
        assertEquals(4, result.getComponentResults().get(0).getIterationCount());

        assertVoltageEquals(12.589, b10);
        assertVoltageEquals(5.516, b991);
        assertVoltageEquals(5.66, b992);
        assertReactivePowerEquals(-5.715, g991.getTerminal());
        assertReactivePowerEquals(-5.143, g992.getTerminal());

        // the zone has no control unit left, so it is not created at all
        LfNetworkParameters networkParameters = new LfNetworkParameters().setSecondaryVoltageControl(true);
        assertTrue(LfNetwork.load(network, new LfNetworkLoaderImpl(), networkParameters)
                .get(0).getSecondaryVoltageControls().isEmpty());

        // With secondary voltage set to false, check that the remote control remains active
        parametersExt.setSecondaryVoltageControl(false);
        result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(4, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(12.8, b6);
        assertVoltageEquals(5.52, b991);
        // Compare voltage in pu
        assertEquals(1.066, b6.getV() / b6.getVoltageLevel().getNominalV(), 1e-3);
        assertEquals(0.92, b991.getV() / b991.getVoltageLevel().getNominalV(), 1e-3);
    }

    private static double qToLevel(Generator g) {
        ReactiveLimits limits = g.getReactiveLimits();
        double q = -g.getTerminal().getQ();
        double p = -g.getTerminal().getP();
        return q / ((limits.getMaxQ(p) - limits.getMinQ(p)) / 2);
    }

    @Test
    void testQQrAlignmentMode() {
        // g6 [-6, 24] and g8 [-6, 200] have asymmetric reactive diagrams: aligning k (relative position in the
        // diagram) and aligning q / qr (reactive power shared proportionally to the participation factors, as the
        // Dynawo/DynaFlow reactive power control loops do) give different dispatches
        parameters.setUseReactiveLimits(false);
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();
        parametersExt.setSecondaryVoltageControl(true)
                .setSecondaryVoltageControlAlignmentMode(LfSecondaryVoltageControl.AlignmentMode.Q_QR);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertVoltageEquals(13, b10);
        // same level q / qr for both units, with qr = half of the reactive range by default
        assertEquals(qToLevel(g6), qToLevel(g8), 1e-3);
        assertEquals(6, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(13.017, b6);
        assertVoltageEquals(23.627, b8);
        assertReactivePowerEquals(-7.188, g6.getTerminal()); // 0.479 * 15 Mvar
        assertReactivePowerEquals(-49.356, g8.getTerminal()); // 0.479 * 103 Mvar
        assertEquals(0.479, qToLevel(g6), DELTA_POWER);
        assertEquals(0.479, qToLevel(g8), DELTA_POWER);
        // whereas the k values of the two units are not aligned (compare with testNoReactiveLimits: k = -0.412 for both)
        assertEquals(-0.121, qToK(g6), DELTA_POWER);
        assertEquals(-0.463, qToK(g8), DELTA_POWER);
    }

    @Test
    void testQQrAlignmentModeSaturation() {
        // in Q/Qr mode the level saturates at 1 like in the dynamic models: g6 [-6, 24] cannot be asked more than
        // qr = 15 Mvar and g8 [-6, 200] more than qr = 103 Mvar, so a far pilot point target is not reached, whereas
        // the K mode uses the whole reactive diagrams
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(TARGET_V_SATURATION).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();
        parametersExt.setSecondaryVoltageControl(true)
                .setSecondaryVoltageControlAlignmentMode(LfSecondaryVoltageControl.AlignmentMode.Q_QR);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(20, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(13.798, b10); // 14.5 kV is out of reach with the level saturated at 1
        assertVoltageEquals(13.663, b6);
        assertVoltageEquals(26.486, b8);
        assertReactivePowerEquals(-15, g6.getTerminal()); // qr = (24 + 6) / 2
        assertReactivePowerEquals(-103, g8.getTerminal()); // qr = (200 + 6) / 2
        assertEquals(1, qToLevel(g6), DELTA_POWER);
        assertEquals(1, qToLevel(g8), DELTA_POWER);

        parametersExt.setSecondaryVoltageControlAlignmentMode(LfSecondaryVoltageControl.AlignmentMode.K);
        result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(21, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(14.5, b10); // reached with the whole reactive diagrams
        assertVoltageEquals(14.184, b6);
        assertVoltageEquals(29.108, b8);
        assertReactivePowerEquals(-18.417, g6.getTerminal());
        assertReactivePowerEquals(-161.669, g8.getTerminal());
        assertEquals(0.628, qToK(g6), DELTA_POWER);
        assertEquals(0.628, qToK(g8), DELTA_POWER);
    }

    private void createSaturationZone() {
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(TARGET_V_SATURATION).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();
    }

    @Test
    void testZoneScalingStepClipping() {
        // with a small maximum step the component clipping and the zone scaling follow different paths to the same target
        createSaturationZone();
        parametersExt.setSecondaryVoltageControl(true)
                .setMaxOuterLoopIterations(60)
                .setSecondaryVoltageControlMaxTargetVoltageStep(1);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        int componentIterationCount = result.getComponentResults().get(0).getIterationCount();
        assertVoltageEquals(14.5, b10);
        assertEquals(0.628, qToK(g6), DELTA_POWER);
        assertEquals(0.628, qToK(g8), DELTA_POWER);

        parametersExt.setSecondaryVoltageControlStepClippingMode(SecondaryVoltageControlOuterLoop.StepClippingMode.ZONE_SCALING);
        result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(COMPONENT_VS_SCALING_ITERATION_COUNT, componentIterationCount + "/" + result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(14.5, b10);
        assertEquals(0.628, qToK(g6), DELTA_POWER);
        assertEquals(0.628, qToK(g8), DELTA_POWER);
    }

    @Test
    void testBlockedZoneDetectionAndReArm() {
        // with a tight plausible range the controlled bus target voltages reach the bound before the pilot point target:
        // the zone is given up by the stall detection by default, and as soon as the bound is reached with the blocked
        // zone detection; re-arming does not trigger because nothing else moves the pilot point
        createSaturationZone();
        parametersExt.setSecondaryVoltageControl(true)
                .setMaxPlausibleTargetVoltage(1.05);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        int stallIterationCount = result.getComponentResults().get(0).getIterationCount();
        double stallB10V = b10.getV();
        assertTrue(stallB10V < TARGET_V_SATURATION - 0.1);
        assertVoltageEquals(1.05 * b6.getVoltageLevel().getNominalV(), b6);
        assertVoltageEquals(1.05 * b8.getVoltageLevel().getNominalV(), b8);

        parametersExt.setSecondaryVoltageControlBlockedZoneDetection(true)
                .setSecondaryVoltageControlStallReArm(true);
        result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(STALL_VS_BLOCKED_ITERATION_COUNT, stallIterationCount + "/" + result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(stallB10V, b10);
        assertVoltageEquals(1.05 * b6.getVoltageLevel().getNominalV(), b6);
        assertVoltageEquals(1.05 * b8.getVoltageLevel().getNominalV(), b8);
    }

    @Test
    void testExcludePinnedUnits() {
        // with a tight plausible bound both controlled buses end at the bound: the units are pinned and left out of the
        // coordination as soon as they reach it, so the zone stops in fewer iterations with the same solution
        createSaturationZone();
        parametersExt.setSecondaryVoltageControl(true)
                .setMaxPlausibleTargetVoltage(PINNED_TEST_BOUND);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        String defaultState = result.getComponentResults().get(0).getIterationCount() + "/" + Math.round(b10.getV() * 1000) + "/" + Math.round(b6.getV() * 1000) + "/" + Math.round(b8.getV() * 1000);

        parametersExt.setSecondaryVoltageControlExcludePinnedUnits(true);
        result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        String pinnedState = result.getComponentResults().get(0).getIterationCount() + "/" + Math.round(b10.getV() * 1000) + "/" + Math.round(b6.getV() * 1000) + "/" + Math.round(b8.getV() * 1000);
        assertEquals(PINNED_TEST_EXPECTED, defaultState + " " + pinnedState);
        assertVoltageEquals(PINNED_TEST_BOUND * b8.getVoltageLevel().getNominalV(), b8); // pinned at the bound
    }
}
